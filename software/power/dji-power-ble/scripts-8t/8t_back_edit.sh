#!/bin/bash
# 回编辑页 + 展开 A002
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== menu ==="
"$ADB" -s $DEV shell input tap 72 178 2>&1
sleep 2
"$ADB" -s $DEV shell input tap 394 650 2>&1
sleep 2
echo "=== dropdown select sample ==="
"$ADB" -s $DEV shell input tap 500 185 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/dd.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/dd.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/dd.xml 2>&1 >/dev/null
grep -oE 'Sample configuration' /home/<USER>/.openclaw/workspace/jarvis/tmp/dd.xml | head -1
"$ADB" -s $DEV shell input tap 525 317 2>&1
sleep 2
echo "=== expand A002 ==="
"$ADB" -s $DEV shell input tap 700 1160 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/e5.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/e5.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/e5.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/e5.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(ADD CHARACTERISTIC|UUID: 0xC[0-9]{3}|0xA002)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} at center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
"
