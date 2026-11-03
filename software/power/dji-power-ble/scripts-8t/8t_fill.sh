#!/bin/bash
# 选中 custom + 填 UUID + OK
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
"$ADB" -s $DEV shell input tap 470 1100 2>&1
sleep 1
"$ADB" -s $DEV shell input tap 500 1480 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'A002' 2>&1
sleep 1
"$ADB" -s $DEV shell input keyevent 4 2>&1
sleep 1
"$ADB" -s $DEV shell screencap -p /sdcard/a2.png 2>&1
"$ADB" -s $DEV pull /sdcard/a2.png /home/<USER>/.openclaw/workspace/jarvis/tmp/a2.png 2>&1 | tail -1
