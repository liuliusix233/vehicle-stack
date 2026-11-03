#!/bin/bash
# 8T 服务列表确认
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell uiautomator dump /sdcard/all2.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/all2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/all2.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/all2.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('C30' in t or 'Properties' in t or 'A002' in t or 'Character' in t):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -25
