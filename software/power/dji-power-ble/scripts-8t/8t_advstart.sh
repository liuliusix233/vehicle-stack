#!/bin/bash
# 启动 ADVERTISER 独立广播
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== back to main ==="
"$ADB" -s $DEV shell input keyevent 4 2>&1
sleep 1
"$ADB" -s $DEV shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1
"$ADB" -s $DEV shell uiautomator dump /sdcard/m.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/m.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/m.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/m.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('ADVERT' in t or 'POWER' in t or 'CONNECT' in t or 'RANDOM' in t):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
"
