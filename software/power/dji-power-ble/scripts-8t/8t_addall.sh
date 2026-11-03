#!/bin/bash
# 展开 A002 + 加 C300 (READ)
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== expand A002 ==="
"$ADB" -s $DEV shell input tap 700 1160 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/e3.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/e3.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/e3.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/e3.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(ADD CHARACTERISTIC|ADD DESCRIPTOR|0xC304|UUID: 0xC[0-9]{3})\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} at center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
"
