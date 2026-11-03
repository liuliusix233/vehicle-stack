#!/bin/bash
# 8T 广播状态深度检查
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== last 20 adv events ==="
"$ADB" -s $DEV logcat -d -v threadtime 2>/dev/null | grep -E "onAdvertisingSet|AdvertiseManager|btm_ble_multi_adv" | tail -20
echo "=== bluetooth state ==="
"$ADB" -s $DEV shell dumpsys bluetooth_manager 2>/dev/null | grep -E "state:|enabled|name:" | head -5
echo "=== connected devices ==="
"$ADB" -s $DEV shell dumpsys bluetooth_manager 2>/dev/null | grep -A2 "Bonded devices" | head -8
echo "=== nRF process ==="
"$ADB" -s $DEV shell ps -A 2>/dev/null | grep -i nordic | head -2
