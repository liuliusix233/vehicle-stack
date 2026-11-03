#!/bin/bash
# render_all.sh — build the render job list and drive cdp_shot.py through a
# transient systemd user unit (the exec tool's 2GB RLIMIT_AS kills Chromium).
set -eu
DIR=/home/<USER>/.openclaw/workspace/jarvis/hud-apk/hud-design-refs/proto
SHOTS="$DIR/shots"
mkdir -p "$SHOTS"
python3 - "$DIR" "$SHOTS" > /tmp/hud_jobs.json <<'PY'
import json, sys
base = "file://" + sys.argv[1]; shots = sys.argv[2]
jobs = []
for v in ("s1", "s2"):
    for scale in (1, 2):
        jobs.append({"out": f"{shots}/hero_{v}_{scale}x.png",
                     "url": f"{base}/sport.html?v={v}&frame=14", "w": 480, "h": 240, "scale": scale})
# 低油量告警态 / 换挡提示态
jobs.append({"out": f"{shots}/state_lowfuel_2x.png", "url": f"{base}/sport.html?v=s1&frame=20&fuel=1", "w": 480, "h": 240, "scale": 2})
jobs.append({"out": f"{shots}/state_shift_2x.png",  "url": f"{base}/sport.html?v=s1&frame=12", "w": 480, "h": 240, "scale": 2})
jobs.append({"out": f"{shots}/state_idle_2x.png",   "url": f"{base}/sport.html?v=s1&frame=23", "w": 480, "h": 240, "scale": 2})
# 动态序列（GIF 用）
for i in range(24):
    jobs.append({"out": f"{shots}/f{i:02d}.png", "url": f"{base}/sport.html?v=s1&frame={i}", "w": 480, "h": 240, "scale": 1})
json.dump(jobs, sys.stdout)
PY
systemd-run --user --unit=hudrender --collect --wait --property=Type=oneshot \
  --setenv=HOME=/home/<USER> python3 "$DIR/cdp_shot.py" /tmp/hud_jobs.json 2>&1 | tail -6
