#!/usr/bin/env python3
"""Diagnostics for the 2026-09-28 P report. Reads .cache_preport.json."""
import json, os
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
C = json.load(open(os.path.join(HERE, ".cache_preport.json")))
merged = C["data"]
START = datetime.fromisoformat(C["start"])
END = datetime.fromisoformat(C["end"])
CST = timezone(timedelta(hours=8))
CAP = 2048.0


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


def twa(p, t0=None, t1=None):
    t0 = t0 or p[0][0]
    t1 = t1 or p[-1][0]
    tot = span = 0.0
    for i, (t, v) in enumerate(p):
        t2 = p[i + 1][0] if i + 1 < len(p) else t1
        if t2 <= t0 or t < t0:
            continue
        a, b = max(t, t0), min(t2, t1)
        d = (b - a).total_seconds()
        if d > 0:
            tot += v * d
            span += d
    return (tot / span if span else 0.0), span / 3600


charge, disch, soc, volt = (pts("sensor.cadillac_sdc_charge_w"),
                            pts("sensor.cadillac_discharge_power"),
                            pts("sensor.cadillac_battery"),
                            pts("sensor.cadillac_car_voltage"))

print("entities present:", sorted(merged.keys()))

print("\n=== discharge_power TWA in ~9.2h sub-windows ===")
t = START
while t < END:
    t2 = min(t + timedelta(hours=9.2), END)
    v, h = twa(disch, t, t2)
    c, _ = twa(charge, t, t2)
    s0, s1 = at(soc, t), at(soc, t2)
    print(f"  {t:%m-%d %H:%M}->{t2:%m-%d %H:%M}  out_TWA={v:6.2f}W  in_TWA={c:6.2f}W  "
          f"SOC {s0}->{s1} ({(s1-s0):+.0f}%={(s1-s0)/100*CAP:+.0f}Wh)")
    t = t2

print("\n=== hourly: disch_avg / sdc_avg / SOC / volt ===")
h = START.replace(minute=0, second=0)
while h < END:
    h2 = h + timedelta(hours=1)
    dv, _ = twa(disch, h, h2)
    cv, _ = twa(charge, h, h2)
    s = at(soc, h2)
    v = at(volt, h2)
    print(f"  {h:%m-%d %H}: out={dv:6.2f} in={cv:6.2f}  SOC={s}  V={v}")
    h = h2

print("\n=== charging bursts (sdc>=30W, merged, >=5min) : eta / Wh-per-% ===")
run = None
bursts = []
for tt, v in charge:
    if v >= 30:
        if run is None:
            run = [tt, tt]
        run[1] = tt
    else:
        if run and (run[1] - run[0]).total_seconds() >= 300:
            bursts.append(run)
        run = None
if run:
    bursts.append(run)
for a, b in bursts:
    e, _ = twa(charge, a, b)
    hrs = (b - a).total_seconds() / 3600
    Ein = e * hrs
    s0, s1 = at(soc, a), at(soc, b)
    ds = (s1 - s0) if (s0 is not None and s1 is not None) else None
    Est = ds / 100 * CAP if ds is not None else None
    eff = (Est / Ein * 100) if (Est and Ein) else None
    print(f"  {a:%m-%d %H:%M}->{b:%m-%d %H:%M} {hrs*60:5.1f}min  in={Ein:6.1f}Wh  "
          f"dSOC={ds:+.0f}% ({Est if Est is None else round(Est)}Wh)  eta={None if eff is None else round(eff)}%")

print("\n=== quiet segments: P with and without the small (<30W) input term ===")


def pcalc(a, b):
    c, _ = twa(charge, a, b)
    d, _ = twa(disch, a, b)
    hrs = (b - a).total_seconds() / 3600
    s0, s1 = at(soc, a), at(soc, b)
    if None in (s0, s1):
        return None
    ds = s1 - s0
    Est = ds / 100 * CAP
    P_in = (c - d) * hrs - Est
    P_noin = (-d) * hrs - Est
    return hrs, c, d, ds, P_in / hrs, P_noin / hrs


for a, b in [(datetime(2026, 9, 26, 15, 22, tzinfo=CST), datetime(2026, 9, 26, 22, 34, tzinfo=CST)),
             (datetime(2026, 9, 26, 23, 6, tzinfo=CST), datetime(2026, 9, 27, 4, 5, tzinfo=CST)),
             (datetime(2026, 9, 27, 4, 50, tzinfo=CST), datetime(2026, 9, 27, 14, 9, tzinfo=CST)),
             (datetime(2026, 9, 27, 22, 40, tzinfo=CST), datetime(2026, 9, 28, 8, 32, tzinfo=CST)),
             (datetime(2026, 9, 28, 10, 44, tzinfo=CST), datetime(2026, 9, 28, 19, 59, tzinfo=CST))]:
    r = pcalc(a, b)
    if r:
        hrs, c, d, ds, pi, pn = r
        print(f"  {a:%m-%d %H:%M}->{b:%m-%d %H:%M} {hrs:5.2f}h in={c:5.2f} out={d:5.2f} "
              f"dSOC={ds:+.0f}  P_in={pi:6.2f}W  P_noin={pn:6.2f}W")

print("\n=== capacity cross-check from a clean charge burst pair ===")
print("  (uses 09-27 20:36->21:49 SOC 52->67 and 09-27 17:16->18:38 etc.)")
for a, b in [(datetime(2026, 9, 27, 20, 36, tzinfo=CST), datetime(2026, 9, 27, 21, 49, tzinfo=CST)),
             (datetime(2026, 9, 27, 8, 37, tzinfo=CST), datetime(2026, 9, 27, 9, 53, tzinfo=CST)),
             (datetime(2026, 9, 27, 14, 9, tzinfo=CST), datetime(2026, 9, 27, 15, 0, tzinfo=CST))]:
    c, _ = twa(charge, a, b)
    hrs = (b - a).total_seconds() / 3600
    s0, s1 = at(soc, a), at(soc, b)
    if None in (s0, s1):
        continue
    if s1 > s0:
        Ein = c * hrs
        # plus discharge during burst subtracted from stored
        d, _ = twa(disch, a, b)
        Est = (s1 - s0) / 100 * CAP
        print(f"  {a:%m-%d %H:%M}->{b:%m-%d %H:%M} {hrs:.2f}h in={Ein:.1f}Wh out~={d*hrs:.1f}Wh "
              f"dSOC={s1-s0:+.0f}%({Est:+.0f}Wh) -> eta={(Est+ d*hrs)/Ein*100:.0f}%  implied Wh/1%={Ein/(s1-s0):.1f}")
