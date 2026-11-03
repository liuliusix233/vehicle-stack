#!/bin/bash
# 8T 自己扫描测试（蓝牙硬件）
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== 8T to scanner ==="
"$ADB" -s $DEV shell input keyevent 3 2>&1
sleep 2
"$ADB" -s $DEV shell am start -n no.nordicsemi.android.mcp/no.nordicsemi.android.mcp.MainActivity 2>&1 | tail -1
sleep 4
"$ADB" -s $DEV shell input tap 154 329 2>&1
sleep 5
"$ADB" -s $DEV shell screencap -p /sdcard/8t_scan.png 2>&1
"$ADB" -s $DEV pull /sdcard/8t_scan.png /home/<USER>/.openclaw/workspace/jarvis/tmp/8t_scan.png 2>&1 | tail -1
cd /home/<USER>/.openclaw/workspace/jarvis
python3 tmp/vlm_ask.py /home/<USER>/.openclaw/workspace/jarvis/tmp/8t_scan.png "这是 BLE 扫描列表。列出了哪些设备？有几个设备？设备名和 MAC。如果显示'No devices found'就说没扫到。" 2>&1 | head -15
