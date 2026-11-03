#!/bin/bash
# 8T 广播数据详情
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== advertising data hex (17:51:39 context) ==="
"$ADB" -s $DEV logcat -d -v threadtime 2>/dev/null | grep -B5 -A20 "17:51:39" | grep -iE "data|adv|name|uuid|payload|hex|\[" | head -30
echo "=== OreoAdvertiser logs ==="
"$ADB" -s $DEV logcat -d -v threadtime 2>/dev/null | grep -iE "OreoAdvertiser" | tail -20
