#!/usr/bin/env python3
"""render2.py — render the v2 layout: S1/S2 heroes, font variants, spacing comparison."""
import json, os, subprocess, sys

DIR = "/home/<USER>/.openclaw/workspace/jarvis/hud-apk/hud-design-refs/proto"
base = "file://" + DIR
S = DIR + "/shots"
jobs = []
for v in ("s1", "s2"):
    for sc in (1, 2):
        jobs.append({"out": f"{S}/v2_{v}_{sc}x.png", "url": f"{base}/sport2.html?v={v}&frame=14", "w": 480, "h": 240, "scale": sc})
# 字体变体（S1，2×）
for f in ("saira", "chakra", "barlow"):
    jobs.append({"out": f"{S}/v2_s1_font_{f}.png", "url": f"{base}/sport2.html?v=s1&frame=14&f={f}", "w": 480, "h": 240, "scale": 2})
# 状态：待机 / 换挡提示 / 低油量
jobs.append({"out": f"{S}/v2_state_idle.png", "url": f"{base}/sport2.html?v=s1&frame=23", "w": 480, "h": 240, "scale": 1})
jobs.append({"out": f"{S}/v2_state_shift.png", "url": f"{base}/sport2.html?v=s1&frame=12", "w": 480, "h": 240, "scale": 1})
jobs.append({"out": f"{S}/v2_state_lowfuel.png", "url": f"{base}/sport2.html?v=s1&frame=20&fuel=1", "w": 480, "h": 240, "scale": 1})
# GIF 帧
for i in range(24):
    jobs.append({"out": f"{S}/g{i:02d}.png", "url": f"{base}/sport2.html?v=s1&frame={i}", "w": 480, "h": 240, "scale": 1})

json.dump(jobs, open("/tmp/jobs_v2.json", "w"))
print("jobs:", len(jobs))
