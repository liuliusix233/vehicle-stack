#!/bin/bash
# 播放 + duration OK + 验证广播数据
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== clear logcat ==="
"$ADB" -s $DEV logcat -c 2>&1
echo "=== tap play (984,509) ==="
"$ADB" -s $DEV shell input tap 984 509 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/du.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/du.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/du.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/du.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(OK|CANCEL|Until manually[^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
"
