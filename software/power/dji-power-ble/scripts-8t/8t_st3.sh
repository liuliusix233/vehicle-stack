#!/bin/bash
# 当前状态
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell uiautomator dump /sdcard/st3.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/st3.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/st3.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/st3.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('ADD SERVICE' in t or '0xA00' in t or 'OK' in t or 'CANCEL' in t or 'Add service' in t):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -10
