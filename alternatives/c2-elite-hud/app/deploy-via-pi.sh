#!/bin/bash
# 部署 HudDashboard.apk 到 HUD（经 Pi 跳板）— 避免 ssh 嵌套引号被吞
# 用法: deploy-via-pi.sh [HUD_IP]  (默认 <LAN_IP>，IP 漂移时传新值)
set -e
APK=/home/<USER>/.openclaw/workspace/jarvis/hud-apk/dist/HudDashboard.apk
IP="${1:-<LAN_IP>}"
sshpass -p 953261874 scp -o StrictHostKeyChecking=no "$APK" pi@<TS_IP>:/home/<USER>/hud_new.apk
sshpass -p 953261874 ssh -o StrictHostKeyChecking=no pi@<TS_IP> \
  "ADB=/usr/bin/adb; IP=$IP; \
   \$ADB connect \$IP:5555; \
   \$ADB -s \$IP:5555 push /home/<USER>/hud_new.apk /data/local/tmp/hud.apk && \
   \$ADB -s \$IP:5555 shell pm install -r /data/local/tmp/hud.apk && \
   \$ADB -s \$IP:5555 shell 'am force-stop com.vox.hud && sleep 2 && am start -n com.vox.hud/.MainActivity' && \
   \$ADB -s \$IP:5555 shell dumpsys package com.vox.hud | grep lastUpdateTime"
