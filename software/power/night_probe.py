#!/usr/bin/env python3
"""Probe cached history: all channels, segment energies, SOC-slope. Read-only."""
import json, os
from datetime import datetime, timedelta, timezone

CST = timezone(timedelta(hours=8))
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache_night.json")
CAP = 2048.0
with open(CACHE) as f:
    c = json.load(f)
START = datetime.fromisoformat(c["start"])
END = datetime.fromisoformat(c["end"])
merged = c["data"]


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


S = {k: pts("sensor.cadillac_" + k) for k in
     ["sdc_charge_w", "charge_power", "discharge_power", "sdc_discharge_w", "battery", "car_voltage"]}

print("entity stats over window:")
for k, p in S.items():
    if not p:
        print(f"  {k}: NO DATA")
        continue
    nums = [v for _, v in p]
    print(f"  {k}: n={len(p)} min={min(nums):.2f} max={max(nums):.2f} avg={sum(nums)/len(nums):.2f}")

# per-hour averages (time-sampled, minute grid)
print("\nhh      sdc_c  chgP  dischP sdc_d  SOC  volt")
for h in range(int((END - START).total_seconds() // 3600) + 1):
    t0 = START + timedelta(hours=h)
    if t0 >= END:
        break
    acc = {k: [] for k in S}
    for i in range(60):
        t = t0 + timedelta(minutes=i)
        if t >= END:
            break
        for k, p in S.items():
            v = at(p, t)
            if v is not None:
                acc[k].append(v)
    def m(k):
        v = acc[k]
        return sum(v) / len(v) if v else float("nan")
    print(f"  {t0:%m-%d %H}  {m('sdc_charge_w'):5.2f} {m('charge_power'):5.1f} {m('discharge_power'):6.2f} "
          f"{m('sdc_discharge_w'):5.2f} {m('battery'):5.1f} {m('car_voltage'):5.2f}")

# segment energies + SOC brackets
segs = [(datetime(2026, 9, 26, 14, 12, tzinfo=CST), datetime(2026, 9, 26, 15, 20, tzinfo=CST)),
        (datetime(2026, 9, 26, 22, 34, tzinfo=CST), datetime(2026, 9, 26, 23, 6, tzinfo=CST)),
        (datetime(2026, 9, 27, 4, 5, tzinfo=CST), datetime(2026, 9, 27, 4, 49, tzinfo=CST))]
print("\ncharging segments:")
for a, b in segs:
    e = sum((at(S["sdc_charge_w"], a + timedelta(minutes=i)) or 0) / 60.0
            for i in range(int((b - a).total_seconds() // 60)))
    ep = sum((at(S["charge_power"], a + timedelta(minutes=i)) or 0) / 60.0
             for i in range(int((b - a).total_seconds() // 60)))
    s0 = at(S["battery"], a - timedelta(minutes=10))
    s1 = at(S["battery"], b + timedelta(minutes=10))
    print(f"  {a:%m-%d %H:%M}-{b:%H:%M} E_sdc={e:.1f}Wh E_chgP={ep:.1f}Wh SOC {s0}->{s1}")

# quiet night slope: SOC change per hour, no charging
print("\nquiet stretches (no charging at all) SOC delta:")
marks = [(datetime(2026, 9, 26, 23, 40, tzinfo=CST), datetime(2026, 9, 27, 3, 45, tzinfo=CST)),
         (datetime(2026, 9, 26, 18, 45, tzinfo=CST), datetime(2026, 9, 26, 21, 3, tzinfo=CST)),
         (datetime(2026, 9, 27, 5, 30, tzinfo=CST), datetime(2026, 9, 27, 7, 54, tzinfo=CST))]
for a, b in marks:
    hrs = (b - a).total_seconds() / 3600
    s0, s1 = at(S["battery"], a), at(S["battery"], b)
    ed = sum((at(S["discharge_power"], a + timedelta(minutes=i)) or 0) / 60.0
             for i in range(int((b - a).total_seconds() // 60)))
    d = s1 - s0
    tot = -d / 100.0 * CAP
    print(f"  {a:%m-%d %H:%M}->{b:%H:%M} {hrs:.2f}h SOC {s0}->{s1} ({d:+.0f}%) "
          f"battery_delta={-tot:+.1f}Wh recorded_out={ed:.1f}Wh "
          f"=> implied_unrecorded={(-tot-ed)/hrs:.2f}W  (if CAP=2048)")

# SOC sample listing around the window
print("\nSOC samples:", " ".join(f"{t:%H:%M}={v:.0f}" for t, v in S["battery"]))
