#!/bin/bash
# 改 8T 蓝牙名 + 重启蓝牙
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== set bluetooth name ==="
"$ADB" -s $DEV shell settings put secure bluetooth_name "POWER1000MINI-1009NX" 2>&1
"$ADB" -s $DEV shell settings get secure bluetooth_name 2>&1
echo "=== restart bluetooth ==="
"$ADB" -s $DEV shell svc bluetooth disable 2>&1
sleep 3
"$ADB" -s $DEV shell svc bluetooth enable 2>&1
sleep 5
echo "=== verify name ==="
"$ADB" -s $DEV shell settings get secure bluetooth_name 2>&1
"$ADB" -s $DEV shell dumpsys bluetooth_manager 2>/dev/null | grep -E "name:" | head -3
echo "=== done ==="
