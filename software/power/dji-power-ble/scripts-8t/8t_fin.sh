#!/bin/bash
# 特征列表确认
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell uiautomator dump /sdcard/fin.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/fin.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/fin.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/fin.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(UUID: 0xC[0-9]{3}|Properties:[^\"]*|ADD CHARACTERISTIC)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} at center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
"
