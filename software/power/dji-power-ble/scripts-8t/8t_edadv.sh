#!/bin/bash
# 编辑广播配置
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
"$ADB" -s $DEV shell input tap 424 460 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/edadv.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/edadv.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/edadv.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/edadv.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -15
