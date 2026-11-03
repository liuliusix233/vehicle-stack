#!/bin/bash
# 点菜单 + 找 GATT Server
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== tap menu ==="
"$ADB" -s $DEV shell input tap 75 87 2>&1
sleep 2
"$ADB" -s $DEV shell screencap -p /sdcard/8t3.png 2>&1
"$ADB" -s $DEV pull /sdcard/8t3.png /home/<USER>/.openclaw/workspace/jarvis/tmp/8t3.png 2>&1 | tail -1
"$ADB" -s $DEV shell uiautomator dump /sdcard/8t3.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/8t3.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/8t3.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/8t3.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -25
