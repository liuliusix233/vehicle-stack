#!/bin/bash
# 当前状态
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
"$ADB" -s $DEV shell uiautomator dump /sdcard/nw.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/nw.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/nw.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/nw.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('C30' in t or 'Character' in t or 'OK' in t or 'CANCEL' in t or 'Add' in t or 'descriptor' in t.lower()):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -15
