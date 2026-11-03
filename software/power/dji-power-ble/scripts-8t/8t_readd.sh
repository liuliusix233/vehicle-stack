#!/bin/bash
# 重新添加 A002 服务（完整流程）
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== ADD SERVICE ==="
"$ADB" -s $DEV shell input tap 178 1388 2>&1
sleep 2
"$ADB" -s $DEV shell input tap 470 1100 2>&1
sleep 1
echo "=== UUID A002 ==="
"$ADB" -s $DEV shell input tap 500 1480 2>&1
sleep 1
"$ADB" -s $DEV shell input text 'A002' 2>&1
sleep 1
"$ADB" -s $DEV shell input keyevent 4 2>&1
sleep 1
"$ADB" -s $DEV shell uiautomator dump /sdcard/re1.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/re1.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/re1.xml 2>&1 >/dev/null
OKY=$(python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/re1.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'text=\"OK\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
print((int(m.group(2))+int(m.group(4)))//2 if m else 0)
")
echo "OK y=$OKY"
if [ "$OKY" != "0" ]; then
  "$ADB" -s $DEV shell input tap 884 $OKY 2>&1
  sleep 2
  echo "=== A002 added, check ==="
  "$ADB" -s $DEV shell uiautomator dump /sdcard/re2.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/re2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/re2.xml 2>&1 >/dev/null
  grep -oE '0xA002|ADD SERVICE' /home/<USER>/.openclaw/workspace/jarvis/tmp/re2.xml | sort -u
else
  echo "dialog not found"
fi
