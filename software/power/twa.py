#!/usr/bin/env python3
"""Time-weighted average (TWA) of HA sensors — a real average power.

Run with HA_TOKEN in env (source tools/ha_utils.sh && export HA_TOKEN).
Usage: twa.py <hours_back> <entity[,entity...]>
Each sample is held until the next one, so the result is a time average,
not a sample-count average (the daemon pushes extra samples when values flicker).
"""
import json, os, sys, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

TOKEN = os.environ["HA_TOKEN"]
URL = "http://<TS_IP>:8123"
TZ = timezone(timedelta(hours=8))

hours = float(sys.argv[1])
ents = sys.argv[2].split(",")
start = (datetime.now(TZ) - timedelta(hours=hours)).isoformat(timespec="seconds")
qs = urllib.parse.urlencode({"filter_entity_id": ",".join(ents), "minimal_response": "1"})
req = urllib.request.Request(f"{URL}/api/history/period/{start}?{qs}")
req.add_header("Authorization", "Bearer " + TOKEN)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
data = json.loads(opener.open(req, timeout=90).read())

print(f"window: {start} -> now ({hours}h)")
for series in data:
    if not series:
        continue
    eid = series[0].get("entity_id", "?")
    pts = []
    for row in series:
        try:
            v = float(row["state"])
        except (KeyError, ValueError, TypeError):
            continue
        pts.append((datetime.fromisoformat(row["last_changed"]), v))
    if not pts:
        print(f"\n{eid}: no numeric data")
        continue
    pts.sort()
    end = datetime.now(timezone.utc)
    tot = 0.0
    span = 0.0
    hist = {}
    for i, (t, v) in enumerate(pts):
        t2 = pts[i + 1][0] if i + 1 < len(pts) else end
        d = (t2 - t).total_seconds()
        if d <= 0:
            continue
        tot += v * d
        span += d
        hist[round(v)] = hist.get(round(v), 0) + d
    if span <= 0:
        print(f"\n{eid}: zero span")
        continue
    print(f"\n{eid}  TWA = {tot/span:.2f} W   (span {span/3600:.2f}h, {len(pts)} samples)")
    print("  time share:", {f"{k}W": round(v / span * 100) for k, v in sorted(hist.items())})
