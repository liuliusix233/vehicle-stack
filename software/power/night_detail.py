#!/usr/bin/env python3
"""Detailed breakdown for Power2000 night balance (cached history fetch).

Usage: night_detail.py <start_iso> <end_iso> [--refresh]
Cache: power_monitor/.cache_night.json
"""
import json, os, sys, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

TOKEN = os.environ["HA_TOKEN"]
URL = "http://<TS_IP>:8123"
CST = timezone(timedelta(hours=8))
START = datetime.fromisoformat(sys.argv[1])
END = datetime.fromisoformat(sys.argv[2])
REFRESH = "--refresh" in sys.argv
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache_night.json")
CAP = 2048.0

ENTS = ["sensor.cadillac_sdc_charge_w", "sensor.cadillac_charge_power",
        "sensor.cadillac_discharge_power", "sensor.cadillac_sdc_discharge_w",
        "sensor.cadillac_battery", "sensor.cadillac_car_voltage"]

merged = None
if os.path.exists(CACHE) and not REFRESH:
    with open(CACHE) as f:
        c = json.load(f)
    if c.get("start") == START.isoformat() and c.get("end") == END.isoformat():
        merged = c["data"]
        print(f"[cache] {CACHE}")

if merged is None:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    merged = {}
    _cur = START
    while _cur < END:
        _nxt = min(_cur + timedelta(hours=3), END)
        qs = urllib.parse.urlencode({"filter_entity_id": ",".join(ENTS),
                                     "minimal_response": "1",
                                     "end_time": _nxt.isoformat(timespec="seconds")})
        req = urllib.request.Request(
            f"{URL}/api/history/period/{_cur.isoformat(timespec='seconds')}?{qs}")
        req.add_header("Authorization", "Bearer " + TOKEN)
        try:
            for s in json.loads(opener.open(req, timeout=180).read()):
                if s:
                    merged.setdefault(s[0].get("entity_id", "?"), []).extend(s)
        except Exception as e:
            print(f"  page {_cur:%m-%d %H:%M} failed: {e}")
        _cur = _nxt
    with open(CACHE, "w") as f:
        json.dump({"start": START.isoformat(), "end": END.isoformat(), "data": merged}, f)
    print(f"[fetched] cached -> {CACHE}")


def pts(eid):
    seen, out = set(), []
    for r in merged.get(eid, []):
        k = (r.get("last_changed"), r.get("state"))
        if k in seen:
            continue
        seen.add(k)
        try:
            v = float(r["state"])
        except (KeyError, ValueError, TypeError):
            continue
        out.append((datetime.fromisoformat(r["last_changed"]).astimezone(CST), v))
    out.sort(key=lambda x: x[0])
    return out


def at(p, t):
    if not p or t < p[0][0]:
        return None
    lo, hi = 0, len(p) - 1
    while lo < hi:
        m = (lo + hi + 1) // 2
        if p[m][0] <= t:
            lo = m
        else:
            hi = m - 1
    return p[lo][1]


charge, cpow = pts("sensor.cadillac_sdc_charge_w"), pts("sensor.cadillac_charge_power")
disch, sdis = pts("sensor.cadillac_discharge_power"), pts("sensor.cadillac_sdc_discharge_w")
soc, volt = pts("sensor.cadillac_battery"), pts("sensor.cadillac_car_voltage")

# ---- minute grid ----
KW = 30.0
E_in = E_out = E_in_all = E_out_all = 0.0
E_in_excl = 0.0
n_keep = n_excl = 0
hour = {}
for i in range(int((END - START).total_seconds() // 60)):
    t = START + timedelta(minutes=i)
    c = at(charge, t)
    d = at(disch, t)
    k = t.strftime("%m-%d %H")
    h = hour.setdefault(k, {"in": 0.0, "out": 0.0, "nin": 0, "nex": 0, "cmax": 0.0,
                            "dsum": 0.0, "dn": 0, "v": []})
    c = 0.0 if c is None else c
    d = 0.0 if d is None else d
    E_in_all += c / 60.0
    E_out_all += d / 60.0
    if c >= KW:
        n_excl += 1
        E_in_excl += c / 60.0
        h["nex"] += 1
    else:
        n_keep += 1
        E_in += c / 60.0
        E_out += d / 60.0
        h["in"] += c / 60.0
        h["out"] += d / 60.0
        h["nin"] += 1
    h["cmax"] = max(h["cmax"], c)
    h["dsum"] += d
    h["dn"] += 1
    v = at(volt, t)
    if v is not None:
        h["v"].append(v)

dt_keep = n_keep / 60.0
dt_all = (n_keep + n_excl) / 60.0
print(f"\nwindow {START:%m-%d %H:%M} -> {END:%m-%d %H:%M}  ({(END-START).total_seconds()/3600:.2f}h)")
print(f"minutes: all={n_keep+n_excl} kept={n_keep} ({n_keep/(n_keep+n_excl)*100:.1f}%) excluded={n_excl} ({n_excl/(n_keep+n_excl)*100:.1f}%)")
print(f"E_in_all={E_in_all:.1f}Wh  E_in_kept={E_in:.1f}Wh  E_in_excluded={E_in_excl:.1f}Wh")
print(f"E_out_all={E_out_all:.1f}Wh  E_out_kept={E_out:.1f}Wh")
print(f"avg discharge: all={E_out_all/dt_all:.2f}W  kept={E_out/dt_keep:.2f}W")

soc0, soc1 = at(soc, START), at(soc, END)
ds = None if soc0 is None or soc1 is None else soc1 - soc0
print(f"SOC {soc0} -> {soc1}  dSOC={ds}  (dt_keep={dt_keep:.2f}h dt_all={dt_all:.2f}h)")
if ds is not None:
    Es = ds / 100.0 * CAP
    print(f"P(kept-rule)   = {(E_in - E_out - Es)/dt_keep:.2f} W   [instr. formula]")
    print(f"P(all minutes) = {(E_in_all - E_out_all - Es)/dt_all:.2f} W   [no exclusion]")
    for dd in (-1, 1):
        print(f"   dSOC{dd:+d}%: kept={(E_in-E_out-(ds+dd)/100*CAP)/dt_keep:.2f}W  all={(E_in_all-E_out_all-(ds+dd)/100*CAP)/dt_all:.2f}W")

print("\nSOC samples (CST):")
print("  " + "  ".join(f"{t:%m-%d %H:%M}={v:.0f}" for t, v in soc))

print("\nhour      E_in  E_out  n_keep n_excl sdc_max  disch_avg  volt_mean/min")
for k in sorted(hour):
    h = hour[k]
    va = sum(h["v"]) / len(h["v"]) if h["v"] else float("nan")
    vn = min(h["v"]) if h["v"] else float("nan")
    print(f"  {k}  {h['in']:6.2f} {h['out']:6.1f}  {h['nin']:4d}   {h['nex']:4d}   {h['cmax']:6.1f}   {h['dsum']/h['dn']:6.2f}    {va:6.2f}/{vn:5.2f}")

# voltage slope over quiet night (23:00 -> 04:00)
nv = [(t, v) for t, v in volt if datetime(2026, 9, 26, 23, 0, tzinfo=CST) <= t <= datetime(2026, 9, 27, 4, 0, tzinfo=CST)]
if len(nv) > 1:
    hrs = (nv[-1][0] - nv[0][0]).total_seconds() / 3600
    print(f"\nnight 23:00-04:00 voltage: {nv[0][1]:.3f} -> {nv[-1][1]:.3f}  slope={(nv[-1][1]-nv[0][1])/hrs:+.4f} V/h min={min(v for _,v in nv):.3f} max={max(v for _,v in nv):.3f} n={len(nv)}")

# major sdc segments (>=30W) merged, >=2min
print("\nsdc >=30W segments (major):")
run = None
for t, v in charge:
    if v >= KW:
        if run is None:
            run = [t, t, v, 1]
        else:
            run[1], run[2], run[3] = t, max(run[2], v), run[3] + 1
    else:
        if run and (run[1] - run[0]).total_seconds() >= 120:
            print(f"  {run[0]:%m-%d %H:%M} -> {run[1]:%m-%d %H:%M} "
                  f"{(run[1]-run[0]).total_seconds()/60:.1f}min peak={run[2]:.0f}W")
        run = None

# low-power SDC activity (<30W) : total energy and active share
act = sum(1 for t in range(int((END-START).total_seconds()//60))
          if (lambda c: c is not None and 0.5 <= c < KW)(at(charge, START + timedelta(minutes=t))))
print(f"\nlow-power (<30W, >=0.5W) minutes = {act}  energy={E_in:.1f}Wh  avg_over_active={E_in/(act/60) if act else 0:.2f}W")
