#!/bin/bash
# 填 UUID + 勾属性
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== UUID ==="
"$ADB" -s $DEV shell input tap 500 637 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'C304' 2>&1
sleep 1
echo "=== properties: write without response ==="
"$ADB" -s $DEV shell input tap 385 1083 2>&1
sleep 1
echo "=== properties: write ==="
"$ADB" -s $DEV shell input tap 230 1218 2>&1
sleep 1
echo "=== screenshot ==="
"$ADB" -s $DEV shell screencap -p /sdcard/char1.png 2>&1
"$ADB" -s $DEV pull /sdcard/char1.png /home/<USER>/.openclaw/workspace/jarvis/tmp/char1.png 2>&1 | tail -1
