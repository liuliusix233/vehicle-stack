#!/bin/bash
# 点 ADD SERVICE
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell input tap 178 1174 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/as.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/as.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/as.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/as.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -20
