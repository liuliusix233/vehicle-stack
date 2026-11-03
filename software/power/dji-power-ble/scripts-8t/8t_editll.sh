#!/bin/bash
# 编辑 Link Loss -> Custom A003
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== tap Link Loss pencil ==="
"$ADB" -s $DEV shell input tap 1005 1385 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/es.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/es.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/es.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/es.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('Edit' in t or 'Custom' in t or 'Link Loss' in t or 'UUID' in t or 'OK' in t or 'Name' in t):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
"
