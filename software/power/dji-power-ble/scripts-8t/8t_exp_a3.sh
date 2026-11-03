#!/bin/bash
# 多点试展开 A003
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
for y in 1370 1400 1430 1450; do
  "$ADB" -s $DEV shell input tap 700 $y 2>&1
  sleep 1
  "$ADB" -s $DEV shell uiautomator dump /sdcard/t.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/t.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/t.xml 2>&1 >/dev/null
  if grep -q "ADD CHARACTERISTIC" /home/<USER>/.openclaw/workspace/jarvis/tmp/t.xml 2>/dev/null; then
    echo "EXPANDED at y=$y"
    break
  fi
done
grep -oE 'ADD CHARACTERISTIC' /home/<USER>/.openclaw/workspace/jarvis/tmp/t.xml | head -1
