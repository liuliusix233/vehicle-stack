#!/bin/bash
# 添加 A003 服务
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== ADD SERVICE ==="
"$ADB" -s $DEV shell input tap 178 1388 2>&1
sleep 2
"$ADB" -s $DEV shell input tap 470 1100 2>&1
sleep 1
"$ADB" -s $DEV shell input tap 500 1480 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'A003' 2>&1
sleep 1
"$ADB" -s $DEV shell input keyevent 4 2>&1
sleep 1
"$ADB" -s $DEV shell uiautomator dump /sdcard/a3d.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/a3d.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/a3d.xml 2>&1 >/dev/null
OKY=$(python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/a3d.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'text=\"OK\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
print((int(m.group(2))+int(m.group(4)))//2 if m else 0)
")
echo "OK y=$OKY"
[ "$OKY" != "0" ] && "$ADB" -s $DEV shell input tap 884 $OKY 2>&1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/a3c.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/a3c.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/a3c.xml 2>&1 >/dev/null
grep -oE '0xA003' /home/<USER>/.openclaw/workspace/jarvis/tmp/a3c.xml | head -1
