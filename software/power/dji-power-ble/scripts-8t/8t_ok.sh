#!/bin/bash
# 填 UUID A002 + OK（不用返回键）
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell input tap 500 1480 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'A002' 2>&1
sleep 1
echo "=== screenshot before OK ==="
"$ADB" -s $DEV shell screencap -p /sdcard/preok.png 2>&1
"$ADB" -s $DEV pull /sdcard/preok.png /home/<USER>/.openclaw/workspace/jarvis/tmp/preok.png 2>&1 | tail -1
echo "=== OK (884,1625) ==="
"$ADB" -s $DEV shell input tap 884 1625 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/postok.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/postok.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/postok.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/postok.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -15
