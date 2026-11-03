#!/bin/bash
# 8T 蓝牙状态 + 广播日志
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== bluetooth state ==="
"$ADB" -s $DEV shell dumpsys bluetooth_manager 2>/dev/null | grep -E "state:|enabled" | head -3
echo "=== logcat advertising errors ==="
"$ADB" -s $DEV logcat -d -v time 2>/dev/null | grep -iE "advertis|BLE_ADV|startAdvertising|bluetooth.*error|GattServer.*fail" | tail -15
echo "=== logcat bluetooth gatt server recent ==="
"$ADB" -s $DEV logcat -d -v time 2>/dev/null | grep -iE "GattServer|BluetoothGattServer" | tail -10
