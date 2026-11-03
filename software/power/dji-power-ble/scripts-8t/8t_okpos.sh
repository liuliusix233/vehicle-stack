#!/bin/bash
# dump OK 位置 + 点击
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell uiautomator dump /sdcard/okpos.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/okpos.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/okpos.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/okpos.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(OK|CANCEL|INFO)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} at ({x1},{y1})-({x2},{y2}) center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
"
