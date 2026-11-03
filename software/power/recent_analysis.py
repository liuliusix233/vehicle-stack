#!/usr/bin/env python3
"""Per-day energy + battery analysis for a DJI power station entity prefix.

Usage: recent_analysis.py <entity_prefix> <hours_back> [capacity_wh]
Env: HA_TOKEN (source tools/ha_utils.sh then export it).

Bypasses the egress proxy. Paginates the HA history API in <=23h chunks
(REST history returns at most ~24h per call).
"""
import json, os, sys, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

TOKEN = os.environ["HA_TOKEN"]
URL = "http://<TS_IP>:8123"
TZ = timezone(timedelta(hours=8))

prefix = sys.argv[1]
hours = float(sys.argv[2]) if len(sys.argv) > 2 else 168
cap = float(sys.argv[3]) if len(sys.argv) > 3 else 2048

SUFFIXES = ["battery", "remaining", "temperature", "car_voltage",
            "charge_power", "discharge_power", "ac_charge_w", "ac_discharge_w",
            "sdc_charge_w", "sdc_discharge_w", "sdc_voltage", "sdc_type"]
ENTS = [f"sensor.{prefix}_{s}" for s in SUFFIXES]
ENTS.append(f"binary_sensor.{prefix}_sdc_connected")

NOW = datetime.now(TZ)
start_dt = NOW - timedelta(hours=hours)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

merged = {}
cur = start_dt
while cur < NOW:
    nxt = min(cur + timedelta(hours=23), NOW)
    qs = urllib.parse.urlencode({
        "filter_entity_id": ",".join(ENTS),
        "minimal_response": "1",
        "end_time": nxt.isoformat(timespec="seconds"),
    })
    req = urllib.request.Request(
        f"{URL}/api/history/period/{cur.isoformat(timespec='seconds')}?{qs}")
    req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        for series in json.loads(opener.open(req, timeout=90).read()):
            if not series:
                continue
            merged.setdefault(series[0].get("entity_id", "?"), []).extend(series)
    except Exception as e:
        print(f"  page {cur:%m-%d %H:%M} failed: {e}")
    cur = nxt

# dedupe by (last_changed, state), sort by time
series = {}
for eid, rows in merged.items():
    seen = set()
    out = []
    for r in rows:
        k = (r.get("last_changed"), r.get("state"))
        if k in seen:
            continue
        seen.add(k)
        out.append(r)
    out.sort(key=lambda r: r.get("last_changed", ""))
    series[eid] = out

print(f"=== {prefix}  window {start_dt:%Y-%m-%d %H:%M} -> {NOW:%Y-%m-%d %H:%M} ({hours}h) cap={cap}Wh ===")
for eid, rows in sorted(series.items()):
    if not rows:
        print(f"\n{eid}: (none)")
        continue
    vals = []
    for r in rows:
        try:
            vals.append((r["last_changed"], float(r["state"])))
        except (KeyError, ValueError, TypeError):
            pass
    if not vals:
        print(f"\n{eid}: no numeric")
        continue
    print(f"\n{eid}: n={len(vals)} last={vals[-1][1]:.1f} @ {vals[-1][0][11:19]}")

# ---- per-day integration for power channels ----
def integrate(rows, day_key):
    """Return Wh contributed by this entity's samples whose day == day_key."""
    tot = 0.0
    n = len(rows)
    for i, r in enumerate(rows):
        t = r.get("last_changed")
        if t[:10] != day_key:
            continue
        try:
            v = float(r["state"])
        except (KeyError, ValueError, TypeError):
            continue
        t1 = datetime.fromisoformat(t)
        t2 = datetime.fromisoformat(rows[i + 1]["last_changed"]) if i + 1 < n else NOW
        dt = (t2 - t1).total_seconds()
        if dt <= 0:
            continue
        if dt > 3600:  # cap gaps (device offline) to avoid phantom energy
            dt = 0.0
        tot += v * dt
    return tot / 3600.0

# build day list
days = []
d = start_dt.date()
while d <= NOW.date():
    days.append(d.isoformat())
    d += timedelta(days=1)

POWER = {"chg_total": "charge_power", "dis_total": "discharge_power",
         "ac_in": "ac_charge_w", "ac_out": "ac_discharge_w",
         "sdc_in": "sdc_charge_w", "sdc_out": "sdc_discharge_w"}

def key(name):
    return f"sensor.{prefix}_{name}"

print("\n--- per-day Wh ---")
hdr = "day        " + "".join(f"{k:>9}" for k in POWER) + f"{'dSOC%':>7}{'b_lo':>7}{'b_hi':>7}{'b_end':>7}{'cv_lo':>7}"
print(hdr)
for day in days:
    row = f"{day} "
    for label, suffix in POWER.items():
        rows = series.get(key(suffix), [])
        row += f"{integrate(rows, day):9.1f}"
    brows = series.get(key("battery"), [])
    bday = []
    for r in brows:
        try:
            v = float(r["state"])
        except (KeyError, ValueError, TypeError):
            continue
        if r["last_changed"][:10] == day:
            bday.append((r["last_changed"], v))
    if bday:
        lo = min(v for _, v in bday); hi = max(v for _, v in bday); end = bday[-1][1]
        dsoc = hi - lo
        row += f"{dsoc:7.1f}{lo:7.1f}{hi:7.1f}{end:7.1f}"
    else:
        row += f"{'-':>7}{'-':>7}{'-':>7}{'-':>7}"
    crows = []
    for r in series.get(key("car_voltage"), []):
        try:
            v = float(r["state"])
        except (KeyError, ValueError, TypeError):
            continue
        if r["last_changed"][:10] == day:
            crows.append(v)
    row += f"{min(crows):7.2f}" if crows else f"{'-':>7}"
    print(row)

print("\n--- totals over window (Wh) ---")
for label, suffix in POWER.items():
    tot = sum(integrate(series.get(key(suffix), []), day) for day in days)
    print(f"  {label:9} = {tot:10.1f}")
