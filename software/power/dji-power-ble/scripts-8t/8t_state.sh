#!/bin/bash
# 截图当前
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
"$ADB" -s $DEV shell screencap -p /sdcard/st1.png 2>&1
"$ADB" -s $DEV pull /sdcard/st1.png /home/<USER>/.openclaw/workspace/jarvis/tmp/st1.png 2>&1 | tail -1
"$ADB" -s $DEV shell uiautomator dump /sdcard/st1.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/st1.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/st1.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/st1.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -15
