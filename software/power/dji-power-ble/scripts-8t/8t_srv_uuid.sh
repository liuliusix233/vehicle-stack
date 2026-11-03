#!/bin/bash
# 添加 Custom Service A002
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== select custom service ==="
"$ADB" -s $DEV shell input tap 470 1100 2>&1
sleep 1
echo "=== tap UUID field ==="
"$ADB" -s $DEV shell input tap 500 1480 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'A002' 2>&1
sleep 1
"$ADB" -s $DEV shell input keyevent 4 2>&1
sleep 1
echo "=== dump to verify ==="
"$ADB" -s $DEV shell uiautomator dump /sdcard/as2.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/as2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/as2.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/as2.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'class=\"android.widget.EditText\"[^>]*?(?:text=\"([^\"]*)\")?[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'EditText text={t!r} at ({x1},{y1})-({x2},{y2})')
"
