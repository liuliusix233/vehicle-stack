#!/usr/bin/env python3
"""Aggregate HA history for power-station sensors.

Run with HA_TOKEN in the environment (source tools/ha_utils.sh first).
Bypasses the gateway egress proxy so LAN requests go direct.

Usage: drive_peaks.py [hours_back] [entity1,entity2,...]
"""
import json, os, sys, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

TOKEN = os.environ["HA_TOKEN"]
URL = "http://<TS_IP>:8123"
TZ = timezone(timedelta(hours=8))

hours = float(sys.argv[1]) if len(sys.argv) > 1 else 6
REF = os.environ.get("REF_NOW")          # ISO, e.g. 2026-09-19T20:00:00+08:00 (defaults to real now)
NOW = datetime.fromisoformat(REF) if REF else datetime.now(TZ)
DEFAULT = ("sensor.cadillac_sdc_charge_w,sensor.cadillac_sdc_discharge_w,"
           "sensor.cadillac_charge_power,sensor.cadillac_discharge_power,"
           "sensor.cadillac_battery,sensor.cadillac_car_voltage")
ents = (sys.argv[2] if len(sys.argv) > 2 else DEFAULT).split(",")

start_dt = NOW - timedelta(hours=hours)
start = start_dt.isoformat(timespec="seconds")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

# ⚠️ 2026-09-26：HA REST 历史接口实测**只返回「起点后 24h」**（36/48/72h 请求一律只回 ~24h），
# 所以 >24h 的窗必须分页拼接，否则结果只覆盖首 24h（曾导致“7 天回溯”其实只有 1 天）。
_merged = {}
_cur = start_dt
while _cur < NOW:
    _nxt = min(_cur + timedelta(hours=23), NOW)
    _qs = urllib.parse.urlencode({
        "filter_entity_id": ",".join(ents),
        "minimal_response": "1",
        "end_time": _nxt.isoformat(timespec="seconds"),
    })
    _req = urllib.request.Request(
        f"{URL}/api/history/period/{_cur.isoformat(timespec='seconds')}?{_qs}")
    _req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        for _series in json.loads(opener.open(_req, timeout=60).read()):
            if not _series:
                continue
            _merged.setdefault(_series[0].get("entity_id", "?"), []).extend(_series)
    except Exception as _e:
        print(f"  page {_cur:%m-%d %H:%M} failed: {_e}")
    _cur = _nxt

data = []
for _eid, _rows in _merged.items():
    _seen = set()
    _out = []
    for _r in _rows:
        _k = (_r.get("last_changed"), _r.get("state"))
        if _k in _seen:
            continue
        _seen.add(_k)
        _out.append(_r)
    data.append(_out)

print(f"window: {start} -> now ({hours}h)")
for series in data:
    if not series:
        continue
    eid = series[0].get("entity_id", "?")
    vals = []
    for row in series:
        try:
            v = float(row["state"])
        except (KeyError, ValueError, TypeError):
            continue
        vals.append((row.get("last_changed", ""), v))
    if not vals:
        print(f"\n{eid}: no numeric data")
        continue
    nums = [v for _, v in vals]
    mx = max(nums)
    mx_ts = [t for t, v in vals if v == mx][0]
    print(f"\n{eid}")
    print(f"  n={len(nums)}  min={min(nums):.1f}  avg={sum(nums)/len(nums):.1f}  max={mx:.1f}  @ {mx_ts}")
    print(f"  last={vals[-1][1]:.1f} @ {vals[-1][0][:]}")
    per_hour = {}
    per_day = {}
    for t, v in vals:
        k = t[:13]
        per_hour[k] = max(per_hour.get(k, -1e9), v)
        d = t[:10]
        per_day[d] = max(per_day.get(d, -1e9), v)
    if hours <= 26:
        print("  per-hour max:", {k[-5:]: round(v, 1) for k, v in sorted(per_hour.items())})
    print("  per-day max:", {k: round(v, 1) for k, v in sorted(per_day.items())})
    if hours <= 1.5:
        per_min = {}
        for t, v in vals:
            k = t[:16]
            per_min.setdefault(k, []).append(v)
        print("  per-minute (UTC+0 stamp; add 8h for CST):")
        for k, vs in sorted(per_min.items()):
            print(f"    {k} n={len(vs):3d} min={min(vs):6.1f} avg={sum(vs)/len(vs):6.1f} max={max(vs):6.1f}")
        if len(vals) <= 40:
            print("  raw samples:", [(t[11:19], round(v, 1)) for t, v in vals])
    print("  top5:", [(t[11:19], round(v, 1)) for t, v in sorted(vals, key=lambda x: -x[1])[:5]])

    # ---- time-weighted average (a real average power, not a sample-count mean) ----
    _tot = 0.0
    _span = 0.0
    _hist = {}
    _end = datetime.now(timezone.utc)
    for _i, (_t, _v) in enumerate(vals):
        _t1 = datetime.fromisoformat(_t)
        _t2 = datetime.fromisoformat(vals[_i + 1][0]) if _i + 1 < len(vals) else _end
        _d = (_t2 - _t1).total_seconds()
        if _d <= 0:
            continue
        _tot += _v * _d
        _span += _d
        _hist[round(_v)] = _hist.get(round(_v), 0) + _d
    if _span > 0:
        print(f"  TWA = {_tot / _span:.2f} W over {_span / 3600:.2f}h  ({len(vals)} samples)")
        print("  time share:", {f"{k}W": round(v / _span * 100) for k, v in sorted(_hist.items())})
