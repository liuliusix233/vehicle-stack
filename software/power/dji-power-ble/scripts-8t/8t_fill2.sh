#!/bin/bash
# 重来：ADD SERVICE -> custom -> UUID -> 直接 OK（不用返回键）
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell input tap 178 1174 2>&1
sleep 2
"$ADB" -s $DEV shell input tap 470 1100 2>&1
sleep 1
"$ADB" -s $DEV shell input tap 500 1480 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'A002' 2>&1
sleep 1
echo "=== verify before OK ==="
"$ADB" -s $DEV shell screencap -p /sdcard/a3.png 2>&1
"$ADB" -s $DEV pull /sdcard/a3.png /home/<USER>/.openclaw/workspace/jarvis/tmp/a3.png 2>&1 | tail -1
