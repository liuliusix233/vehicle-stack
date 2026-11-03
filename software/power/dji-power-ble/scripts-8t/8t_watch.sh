#!/bin/bash
# 盯 8T GATT 连接日志（30s）
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== watch gatt server connection ==="
"$ADB" -s $DEV logcat -c 2>&1
sleep 30
"$ADB" -s $DEV logcat -d -v threadtime 2>/dev/null | grep -iE "GattServer|BluetoothGattServer|onConnectionStateChange|onCharacteristicWrite|onCharacteristicRead|onNotificationSent|onDescriptorWrite|onServiceAdded" | head -30
