#!/bin/bash
# ADVERTISER + 播放
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== ADVERTISER tab ==="
"$ADB" -s $DEV shell input tap 772 330 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/ad.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/ad.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ad.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/ad.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('POWER' in t or 'CONNECT' in t or 'RANDOM' in t or 'NON-CON' in t):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
"
