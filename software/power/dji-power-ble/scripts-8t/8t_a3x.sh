#!/bin/bash
# 展开 A003 + 加 C400 (READ)
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== expand A003 ==="
"$ADB" -s $DEV shell input tap 700 1385 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/a3x.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/a3x.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/a3x.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/a3x.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(ADD CHARACTERISTIC|0xA003|0xC4[0-9]{2})\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
"
