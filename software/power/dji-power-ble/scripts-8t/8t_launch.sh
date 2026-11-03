#!/bin/bash
# 启动 nRF Connect on 8T
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
"$ADB" -s $DEV shell monkey -p no.nordicsemi.android.mcp -c android.intent.category.LAUNCHER 1 2>&1 | tail -1
sleep 4
"$ADB" -s $DEV shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1
"$ADB" -s $DEV shell screencap -p /sdcard/8t1.png 2>&1
"$ADB" -s $DEV pull /sdcard/8t1.png /home/<USER>/.openclaw/workspace/jarvis/tmp/8t1.png 2>&1 | tail -1
