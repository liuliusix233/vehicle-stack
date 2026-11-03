#!/bin/bash
# 返回配置列表 + 新建
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== back ==="
"$ADB" -s $DEV shell input tap 73 76 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/b1.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/b1.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/b1.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/b1.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -15
