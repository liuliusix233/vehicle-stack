#!/usr/bin/env python3
"""List low-voltage periods + battery/energy sanity for a DJI power station prefix.
Usage: lowvolt.py <entity_prefix> <hours_back> [threshold_V=12.30]"""
import json, os, sys, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

TOKEN = os.environ["HA_TOKEN"]
URL = "http://<TS_IP>:8123"
TZ = timezone(timedelta(hours=8))
prefix = sys.argv[1]
hours = float(sys.argv[2]) if len(sys.argv) > 2 else 168
THR = float(sys.argv[3]) if len(sys.argv) > 3 else 12.30
EMERG = 12.05

NOW = datetime.now(TZ)
start_dt = NOW - timedelta(hours=hours)
ENTS = [f"sensor.{prefix}_car_voltage", f"sensor.{prefix}_battery"]
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
merged = {}
cur = start_dt
while cur < NOW:
    nxt = min(cur + timedelta(hours=23), NOW)
    qs = urllib.parse.urlencode({"filter_entity_id": ",".join(ENTS),
                                 "minimal_response": "1",
                                 "end_time": nxt.isoformat(timespec="seconds")})
    req = urllib.request.Request(
        f"{URL}/api/history/period/{cur.isoformat(timespec='seconds')}?{qs}")
    req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        for s in json.loads(opener.open(req, timeout=90).read()):
            if s:
                merged.setdefault(s[0].get("entity_id", "?"), []).extend(s)
    except Exception as e:
        print("page fail", e)
    cur = nxt

rows = merged.get(f"sensor.{prefix}_car_voltage", [])
rows = sorted({(r.get("last_changed"), r.get("state")): r for r in rows}.values(),
              key=lambda r: r.get("last_changed", ""))
vals = []
for r in rows:
    try:
        vals.append((r["last_changed"], float(r["state"])))
    except (KeyError, ValueError, TypeError):
        pass
print(f"=== {prefix} car_voltage {hours}h: n={len(vals)} thr={THR}V emerg={EMERG}V ===")
if vals:
    nums = [v for _, v in vals]
    print(f"min={min(nums):.2f} max={max(nums):.2f} avg={sum(nums)/len(nums):.2f}")
low = [(ts, v) for ts, v in vals if v < THR]
emg = [(ts, v) for ts, v in vals if v < EMERG]
print(f"samples <{THR}V: {len(low)}   <{EMERG}V: {len(emg)}")
# collapse to periods (gap > 5 min => new period)
def periods(seq):
    out = []
    cur_p = None
    last_t = None
    for ts, v in seq:
        t = datetime.fromisoformat(ts)
        if cur_p and last_t and (t - last_t).total_seconds() <= 300:
            cur_p[1] = ts; cur_p[2] = min(cur_p[2], v); cur_p[4] += 1
        else:
            if cur_p:
                out.append(cur_p)
            cur_p = [ts, ts, v, v, 1]
        last_t = t
    if cur_p:
        out.append(cur_p)
    return out
for a, b, lo, hi, n in periods(low):
    dur = (datetime.fromisoformat(b) - datetime.fromisoformat(a)).total_seconds() / 60
    print(f"  {a[:19]} -> {b[11:19]}  dur={dur:5.1f}min  min={lo:.2f}V  n={n}")
