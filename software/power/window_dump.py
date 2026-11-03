#!/usr/bin/env python3
"""Dump per-bucket min/max for a station prefix over an explicit time window.
Usage: window_dump.py <prefix> <start ISO+08:00> <end ISO+08:00> [bucket_min=5]
Env: HA_TOKEN"""
import json, os, sys, urllib.parse, urllib.request
from datetime import datetime, timedelta

TOKEN = os.environ["HA_" + "TOKEN"]
URL = "http://<TS_IP>:8123"
prefix = sys.argv[1]
t0 = datetime.fromisoformat(sys.argv[2])
t1 = datetime.fromisoformat(sys.argv[3])
bucket = float(sys.argv[4]) if len(sys.argv) > 4 else 5.0

SUF = ["car_voltage", "battery", "charge_power", "discharge_power",
       "sdc_charge_w", "sdc_discharge_w", "temperature"]
ENTS = [f"sensor.{prefix}_{s}" for s in SUF]
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
qs = urllib.parse.urlencode({"filter_entity_id": ",".join(ENTS),
                             "minimal_response": "1",
                             "end_time": t1.isoformat(timespec="seconds")})
req = urllib.request.Request(
    f"{URL}/api/history/period/{t0.isoformat(timespec='seconds')}?{qs}")
req.add_header("Authorization", "Bearer " + TOKEN)
series = {}
for s in json.loads(opener.open(req, timeout=90).read()):
    if s:
        series[s[0].get("entity_id", "?")] = s

samples = {}
for eid, rows in series.items():
    vals = []
    for r in rows:
        try:
            vals.append((datetime.fromisoformat(r["last_changed"]), float(r["state"])))
        except (KeyError, ValueError, TypeError):
            pass
    samples[eid] = vals

nb = int((t1 - t0).total_seconds() / (bucket * 60)) + 1
print(f"=== {prefix} {t0:%m-%d %H:%M} -> {t1:%m-%d %H:%M} (bucket {bucket}m) ===")
hdr = f"{'time':>16}" + "".join(f"{s[:9]:>10}" for s in SUF)
print(hdr)
for i in range(nb):
    b0 = t0 + timedelta(minutes=bucket * i)
    b1 = b0 + timedelta(minutes=bucket)
    cells = []
    for s in SUF:
        vals = [v for t, v in samples.get(f"sensor.{prefix}_{s}", []) if b0 <= t < b1]
        if not vals:
            cells.append("-")
        elif s == "car_voltage":
            cells.append(f"{min(vals):.2f}/{max(vals):.2f}")
        else:
            cells.append(f"{min(vals):.0f}/{max(vals):.0f}")
    print(f"{b0:%m-%d %H:%M:>16}" + "".join(f"{c:>10}" for c in cells))
