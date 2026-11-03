#!/bin/bash
# 回 nRF + 重开对话框
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== back to nRF ==="
"$ADB" -s $DEV shell input keyevent 4 2>&1
sleep 1
"$ADB" -s $DEV shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1
"$ADB" -s $DEV shell screencap -p /sdcard/back1.png 2>&1
"$ADB" -s $DEV pull /sdcard/back1.png /home/<USER>/.openclaw/workspace/jarvis/tmp/back1.png 2>&1 | tail -1
