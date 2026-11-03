#!/bin/bash
# Spinner 选 Custom service
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== open spinner ==="
"$ADB" -s $DEV shell input tap 540 1295 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/sp.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/sp.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/sp.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/sp.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -12
