#!/bin/bash
# 选中 custom + 填 UUID + OK
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
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
