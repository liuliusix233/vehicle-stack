#!/bin/bash
# 展开 A002
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell input tap 700 1160 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/e4.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/e4.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/e4.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/e4.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('C30' in t or 'CHARACTER' in t or 'A002' in t):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
"
