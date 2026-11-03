#!/usr/bin/env python3
"""Power2000 night energy-balance: parasitic drain P.

P = [ E_in - E_out - dSOC%*2048 ] / dt_h

- E_in  = integral of sensor.cadillac_sdc_charge_w over MINUTES whose value < 30 W
          (minutes with sdc_charge_w >= 30 W are dropped entirely; dt shrinks too)
- E_out = integral of sensor.cadillac_discharge_power over the SAME retained minutes
- SOC   = sensor.cadillac_battery (%)

Chunked history fetch (HA REST returns ~24h max per request; long windows time out).
Usage: night_balance.py <start_iso> <end_iso> [chunk_hours]
"""
import json, os, sys, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

TOKEN = os.environ["HA_TOKEN"]
URL = "http://<TS_IP>:8123"
TZ = timezone(timedelta(hours=8))

START = datetime.fromisoformat(sys.argv[1])
END = datetime.fromisoformat(sys.argv[2])
CHUNK_H = float(sys.argv[3]) if len(sys.argv) > 3 else 3.0

ENTS = [
    "sensor.cadillac_sdc_charge_w",
    "sensor.cadillac_discharge_power",
    "sensor.cadillac_battery",
    "sensor.cadillac_car_voltage",
]

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def fetch(start_dt, end_dt, ents):
    qs = urllib.parse.urlencode({
        "filter_entity_id": ",".join(ents),
        "minimal_response": "1",
        "end_time": end_dt.isoformat(timespec="seconds"),
    })
    req = urllib.request.Request(
        f"{URL}/api/history/period/{start_dt.isoformat(timespec='seconds')}?{qs}")
    req.add_header("Authorization", "Bearer " + TOKEN)
    return json.loads(opener.open(req, timeout=180).read())


merged = {}
_cur = START
while _cur < END:
    _nxt = min(_cur + timedelta(hours=CHUNK_H), END)
    try:
        for series in fetch(_cur, _nxt, ENTS):
            if series:
                merged.setdefault(series[0].get("entity_id", "?"), []).extend(series)
    except Exception as e:
        print(f"  page {_cur:%m-%d %H:%M} failed: {e}")
    _cur = _nxt


def series_pts(entity):
    rows = merged.get(entity, [])
    pts = []
    seen = set()
    for r in rows:
        k = (r.get("last_changed"), r.get("state"))
        if k in seen:
            continue
        seen.add(k)
        try:
            v = float(r["state"])
        except (KeyError, ValueError, TypeError):
            continue
        pts.append((datetime.fromisoformat(r["last_changed"]), v))
    pts.sort(key=lambda x: x[0])
    return pts


def value_at(pts, t):
    """last known value at or before t (None if before first sample)."""
    lo, hi = 0, len(pts) - 1
    if not pts or t < pts[0][0]:
        return None
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if pts[mid][0] <= t:
            lo = mid
        else:
            hi = mid - 1
    return pts[lo][1]


charge = series_pts("sensor.cadillac_sdc_charge_w")
disch = series_pts("sensor.cadillac_discharge_power")
soc = series_pts("sensor.cadillac_battery")
volt = series_pts("sensor.cadillac_car_voltage")

for nm, p in (("sdc_charge_w", charge), ("discharge_power", disch),
              ("battery", soc), ("car_voltage", volt)):
    if p:
        print(f"{nm}: n={len(p)} first={p[0][1]}@{p[0][0]:%m-%d %H:%M} "
              f"last={p[-1][1]}@{p[-1][0]:%m-%d %H:%M}")
    else:
        print(f"{nm}: NO DATA")

# ---- minute grid over [START, END] ----
step = timedelta(minutes=1)
E_in = E_out = 0.0
dt_in = 0.0          # retained minutes
dt_excl = 0.0        # dropped minutes (sdc >= 30W)
gap_min = 0.0        # minutes with no sdc sample in window
total_min = 0.0
per_hour = {}
volt_hour = {}
kw = 30.0

_t = START
while _t < END:
    total_min += 1
    c = value_at(charge, _t)
    d = value_at(disch, _t)
    if c is None:
        gap_min += 1
        _t += step
        continue
    if c >= kw:
        dt_excl += 1
    else:
        dt_in += 1
        E_in += c / 60.0
        E_out += (d or 0.0) / 60.0
    k = _t.strftime("%m-%d %H")
    h = per_hour.setdefault(k, {"in": 0.0, "n": 0, "cmax": 0.0})
    if c < kw and c is not None:
        h["in"] += c / 60.0
        h["n"] += 1
    h["cmax"] = max(h["cmax"], c)
    v = value_at(volt, _t)
    if v is not None:
        vh = volt_hour.setdefault(k, [])
        vh.append(v)
    _t += step

dt_h = dt_in / 60.0
soc0 = value_at(soc, START)
soc1 = value_at(soc, END)
dsoc = None if (soc0 is None or soc1 is None) else (soc1 - soc0)

print(f"\nwindow {START:%Y-%m-%d %H:%M} -> {END:%Y-%m-%d %H:%M}")
print(f"total_min={int(total_min)} retained={int(dt_in)} ({dt_in/total_min*100:.1f}%) "
      f"excluded>= {kw:.0f}W={int(dt_excl)} ({dt_excl/total_min*100:.1f}%) "
      f"nodata={int(gap_min)} ({gap_min/total_min*100:.1f}%)")
print(f"E_in(kept)={E_in:.1f}Wh  E_out(kept)={E_out:.1f}Wh  dt={dt_h:.2f}h")
print(f"SOC first={soc0} last={soc1} dSOC={dsoc}")

if dsoc is not None:
    E_soc = dsoc / 100.0 * 2048.0
    P = (E_in - E_out - E_soc) / dt_h if dt_h > 0 else float("nan")
    print(f"E_soc={E_soc:.1f}Wh  =>  P={P:.2f}W")
    # 1% SOC quantisation band
    for delta in (-1.0, 1.0):
        Es = (dsoc + delta) / 100.0 * 2048.0
        Pv = (E_in - E_out - Es) / dt_h
        print(f"   dSOC{delta:+.0f}%: P={Pv:.2f}W")
    # 24h / week projection
    base = 9.0
    print(f"weekly (9.0+P)*168 = {(base + P) * 168:.0f}Wh")

print("\nper-hour  E_in(Wh)  n_min  sdc_max")
for k, h in sorted(per_hour.items()):
    print(f"  {k}  {h['in']:7.2f}  {h['n']:4d}  {h['cmax']:6.1f}")

print("\nper-hour car_voltage mean/min/max")
for k in sorted(volt_hour):
    vs = volt_hour[k]
    print(f"  {k}  mean={sum(vs)/len(vs):.3f}  min={min(vs):.3f}  max={max(vs):.3f}")

if volt:
    # overall slope (V/h) via first/last
    hrs = (volt[-1][0] - volt[0][0]).total_seconds() / 3600
    print(f"\nvoltage slope first->last: {(volt[-1][1]-volt[0][1])/hrs:+.4f} V/h "
          f"({volt[0][1]:.3f}@{volt[0][0]:%m-%d %H:%M} -> {volt[-1][1]:.3f}@{volt[-1][0]:%m-%d %H:%M})")

# sdc charge on/off cycle: transition points
if charge:
    print("\nsdc_charge_w cycle transitions (>=30W on / <30W off):")
    prev_on = charge[0][1] >= kw
    t0 = charge[0][0]
    n_cyc = 0
    for t, v in charge[1:]:
        on = v >= kw
        if on != prev_on:
            dur = (t - t0).total_seconds() / 60.0
            print(f"  {t0:%m-%d %H:%M} -> {t:%m-%d %H:%M} "
                  f"{'ON ' if prev_on else 'off'} {dur:6.1f}min")
            n_cyc += 1
            prev_on = on
            t0 = t
    print(f"  transitions={n_cyc}")
