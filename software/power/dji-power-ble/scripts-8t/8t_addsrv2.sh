#!/bin/bash
# 重新添加 Custom Service
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== ADD SERVICE ==="
"$ADB" -s $DEV shell input tap 178 1174 2>&1
sleep 2
"$ADB" -s $DEV shell screencap -p /sdcard/a1.png 2>&1
"$ADB" -s $DEV pull /sdcard/a1.png /home/<USER>/.openclaw/workspace/jarvis/tmp/a1.png 2>&1 | tail -1
"$ADB" -s $DEV shell uiautomator dump /sdcard/a1.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/a1.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/a1.xml 2>&1 >/dev/null
grep -oE 'Add service|Custom service|Name|UUID|OK|CANCEL' /home/<USER>/.openclaw/workspace/jarvis/tmp/a1.xml | sort -u
