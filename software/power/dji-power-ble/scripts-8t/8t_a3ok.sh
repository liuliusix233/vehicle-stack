#!/bin/bash
# OK + 验证 A003
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
"$ADB" -s $DEV shell input tap 884 1430 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/a3v.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/a3v.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/a3v.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/a3v.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('A00' in t or 'Unknown' in t or 'SERVICE' in t or 'Add service' in t or 'OK' in t or 'CANCEL' in t):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -10
