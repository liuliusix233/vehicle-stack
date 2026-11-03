#!/bin/bash
# 选 Custom + UUID A003 + OK
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== select custom ==="
"$ADB" -s $DEV shell input tap 470 1306 2>&1
sleep 2
echo "=== UUID field ==="
"$ADB" -s $DEV shell input tap 500 1480 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'A003' 2>&1
sleep 1
echo "=== find OK ==="
"$ADB" -s $DEV shell uiautomator dump /sdcard/ok4.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/ok4.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ok4.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/ok4.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(OK|CANCEL)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
m = re.search(r'text=\"([^\"]*A003[^\"]*)\"', xml)
if m: print('UUID content:', m.group(1))
"
