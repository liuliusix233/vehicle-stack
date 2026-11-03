#!/bin/bash
# 加 A003 服务
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== restart nRF + go to server config ==="
"$ADB" -s $DEV shell monkey -p no.nordicsemi.android.mcp -c android.intent.category.LAUNCHER 1 2>&1 | tail -1
sleep 4
echo "=== menu -> configure gatt server ==="
"$ADB" -s $DEV shell input tap 72 178 2>&1
sleep 2
"$ADB" -s $DEV shell input tap 394 650 2>&1
sleep 2
echo "=== dropdown -> sample config ==="
"$ADB" -s $DEV shell input tap 500 185 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/sc2.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/sc2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/sc2.xml 2>&1 >/dev/null
grep -oE 'Sample configuration' /home/<USER>/.openclaw/workspace/jarvis/tmp/sc2.xml | head -1
"$ADB" -s $DEV shell input tap 525 317 2>&1
sleep 2
echo "=== find ADD SERVICE + scroll ==="
"$ADB" -s $DEV shell uiautomator dump /sdcard/fs.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/fs.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/fs.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/fs.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(ADD SERVICE|0xA002|0xA003)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} at center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
"
