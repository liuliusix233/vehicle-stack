#!/bin/bash
# 找 ADD SERVICE 当前位置
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell uiautomator dump /sdcard/fas.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/fas.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/fas.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/fas.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(ADD SERVICE|ADD CHARACTERISTIC|0xA002)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} at ({x1},{y1})-({x2},{y2}) center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
"
