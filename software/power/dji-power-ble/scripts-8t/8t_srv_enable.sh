#!/bin/bash
# 启用 GATT Server（电源按钮）
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== menu ==="
"$ADB" -s $DEV shell input tap 72 178 2>&1
sleep 2
echo "=== configure gatt server ==="
"$ADB" -s $DEV shell input tap 394 650 2>&1
sleep 2
echo "=== open dropdown (power btn visible) ==="
"$ADB" -s $DEV shell input tap 500 185 2>&1
sleep 2
echo "=== tap power button (686,78) ==="
"$ADB" -s $DEV shell input tap 686 78 2>&1
sleep 3
"$ADB" -s $DEV shell screencap -p /sdcard/srv2.png 2>&1
"$ADB" -s $DEV pull /sdcard/srv2.png /home/<USER>/.openclaw/workspace/jarvis/tmp/srv2.png 2>&1 | tail -1
"$ADB" -s $DEV shell uiautomator dump /sdcard/srv2.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/srv2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/srv2.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/srv2.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -10
