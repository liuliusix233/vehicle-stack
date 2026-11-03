#!/bin/bash
# 盲扫 A002 行展开
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
for y in 1160 1225 1280; do
  for x in 500 700 200; do
    "$ADB" -s $DEV shell input tap $x $y 2>&1
    sleep 1
    "$ADB" -s $DEV shell uiautomator dump /sdcard/t.xml 2>&1 >/dev/null
    "$ADB" -s $DEV pull /sdcard/t.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/t.xml 2>&1 >/dev/null
    if grep -qE "ADD CHARACTERISTIC|Add characteristic|CHARACTERISTIC" /home/<USER>/.openclaw/workspace/jarvis/tmp/t.xml 2>/dev/null; then
      echo "EXPANDED at ($x,$y)"
      break 2
    fi
  done
done
echo "=== final state ==="
grep -oE 'ADD CHARACTERISTIC|Add characteristic' /home/<USER>/.openclaw/workspace/jarvis/tmp/t.xml | head -1
