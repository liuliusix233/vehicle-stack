#!/bin/bash
# 添加 A003 v2（无 keyevent 4）
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== ADD SERVICE ==="
"$ADB" -s $DEV shell input tap 178 1388 2>&1
sleep 2
echo "=== custom service spinner ==="
"$ADB" -s $DEV shell input tap 470 1100 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/sp2.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/sp2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/sp2.xml 2>&1 >/dev/null
grep -oE 'Custom service' /home/<USER>/.openclaw/workspace/jarvis/tmp/sp2.xml | head -1
# 如果 spinner 列表开了选 Custom service
"$ADB" -s $DEV shell input tap 470 1306 2>&1
sleep 1
echo "=== UUID ==="
"$ADB" -s $DEV shell input tap 500 1480 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'A003' 2>&1
sleep 1
echo "=== OK (keyboard may cover, find it) ==="
"$ADB" -s $DEV shell uiautomator dump /sdcard/ok3.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/ok3.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ok3.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/ok3.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(OK|CANCEL)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} at ({x1},{y1})-({x2},{y2}) center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
for m in re.finditer(r'text=\"([^\"]*A003[^\"]*)\"', xml):
    print('UUID content:', m.group(1))
"
