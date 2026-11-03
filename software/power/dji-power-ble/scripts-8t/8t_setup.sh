#!/bin/bash
# 连 8T + 装 nRF Connect
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
echo "=== connect 8T ==="
"$ADB" connect <LAN_IP>:39777 2>&1
"$ADB" devices -l 2>&1 | grep -E "39777|device" | head -3
echo "=== install nRF Connect ==="
"$ADB" -s <LAN_IP>:39777 install -r /home/<USER>/.openclaw/workspace/jarvis/tmp/nRF.Connect.4.29.1.apk 2>&1 | tail -2
echo "=== verify ==="
"$ADB" -s <LAN_IP>:39777 shell pm list packages 2>/dev/null | grep -i nordic
echo "=== done ==="
