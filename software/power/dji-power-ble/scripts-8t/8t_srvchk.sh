#!/bin/bash
# 8T GATT Server 状态检查
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== logcat gatt server ==="
"$ADB" -s $DEV logcat -d -v time 2>/dev/null | grep -iE "gatt.*server|GattServer|advertis" | tail -15
echo "=== current screen ==="
"$ADB" -s $DEV shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1
"$ADB" -s $DEV shell screencap -p /sdcard/8t_srv2.png 2>&1
"$ADB" -s $DEV pull /sdcard/8t_srv2.png /home/<USER>/.openclaw/workspace/jarvis/tmp/8t_srv2.png 2>&1 | tail -1
