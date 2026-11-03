#!/bin/bash
# 填广播名 + ADD RECORD
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== display name ==="
"$ADB" -s $DEV shell input tap 500 880 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'POWER1000MINI-1009NX' 2>&1
sleep 1
"$ADB" -s $DEV shell input keyevent 4 2>&1
sleep 1
echo "=== ADD RECORD ==="
"$ADB" -s $DEV shell input tap 297 1063 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/rec.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/rec.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/rec.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/rec.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -15
