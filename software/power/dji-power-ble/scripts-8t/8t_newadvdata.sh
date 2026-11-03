#!/bin/bash
# 8T 新广播数据
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== latest advertising data ==="
"$ADB" -s $DEV logcat -d -v threadtime 2>/dev/null | grep -E "btm_ble_multi_adv.cc\(794\)|is_scan_rsp|onAdvertisingSetStarted|onAdvertisingSetStopped" | tail -12
