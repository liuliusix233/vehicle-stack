#!/bin/bash
# 点 OK 确认添加服务
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell input tap 884 1625 2>&1
sleep 2
"$ADB" -s $DEV shell screencap -p /sdcard/ok1.png 2>&1
"$ADB" -s $DEV pull /sdcard/ok1.png /home/<USER>/.openclaw/workspace/jarvis/tmp/ok1.png 2>&1 | tail -1
"$ADB" -s $DEV shell uiautomator dump /sdcard/ok1.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/ok1.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ok1.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/ok1.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -20
