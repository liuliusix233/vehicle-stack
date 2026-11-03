#!/bin/bash
# 点广播开关 (540,518)
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell input tap 540 518 2>&1
sleep 3
"$ADB" -s $DEV shell uiautomator dump /sdcard/advon.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/advon.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/advon.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/advon.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -10
