#!/bin/bash
# 启用 GATT Server
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== menu ==="
"$ADB" -s $DEV shell input tap 72 178 2>&1
sleep 2
echo "=== configure gatt server ==="
"$ADB" -s $DEV shell input tap 394 650 2>&1
sleep 2
echo "=== dropdown ==="
"$ADB" -s $DEV shell input tap 500 185 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/gsel.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/gsel.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/gsel.xml 2>&1 >/dev/null
grep -oE 'Sample configuration|Custom' /home/<USER>/.openclaw/workspace/jarvis/tmp/gsel.xml | head -3
