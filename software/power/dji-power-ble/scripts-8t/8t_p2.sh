#!/bin/bash
# 截图确认
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
"$ADB" -s $DEV shell screencap -p /sdcard/p2.png 2>&1
"$ADB" -s $DEV pull /sdcard/p2.png /home/<USER>/.openclaw/workspace/jarvis/tmp/p2.png 2>&1 | tail -1
"$ADB" -s $DEV logcat -d -v threadtime 2>/dev/null | grep -E "btm_ble_multi_adv.cc\(794\)|onAdvertisingSet" | tail -8
