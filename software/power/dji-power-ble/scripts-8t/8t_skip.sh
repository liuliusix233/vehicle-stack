#!/bin/bash
# 跳过教程
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
for i in 1 2 3; do
  "$ADB" -s $DEV shell input swipe 900 1400 200 1400 300 2>&1
  sleep 2
done
"$ADB" -s $DEV shell screencap -p /sdcard/8t2.png 2>&1
"$ADB" -s $DEV pull /sdcard/8t2.png /home/<USER>/.openclaw/workspace/jarvis/tmp/8t2.png 2>&1 | tail -1
"$ADB" -s $DEV shell uiautomator dump /sdcard/8t2.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/8t2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/8t2.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/8t2.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -20
