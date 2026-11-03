#!/bin/bash
# 看 8T 当前屏幕
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
"$ADB" -s $DEV shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1
"$ADB" -s $DEV shell screencap -p /sdcard/8t_now.png 2>&1
"$ADB" -s $DEV pull /sdcard/8t_now.png /home/<USER>/.openclaw/workspace/jarvis/tmp/8t_now.png 2>&1 | tail -1
