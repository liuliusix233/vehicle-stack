#!/bin/bash
# 找 A003 行
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell uiautomator dump /sdcard/f3.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/f3.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/f3.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/f3.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('Unknown' in t or 'A00' in t or 'SERVICE' in t or 'ADD' in t):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
"
