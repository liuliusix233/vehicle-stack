#!/bin/bash
# 批量添加特征到 A002
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口

add_char() {
  local UUID=$1 PROPS=$2
  echo "=== add $UUID ($PROPS) ==="
  # 点 ADD CHARACTERISTIC
  "$ADB" -s $DEV shell input tap 280 1667 2>&1
  sleep 2
  # 确认对话框
  "$ADB" -s $DEV shell uiautomator dump /sdcard/cd.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/cd.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/cd.xml 2>&1 >/dev/null
  if ! grep -q "Add characteristic" /home/<USER>/.openclaw/workspace/jarvis/tmp/cd.xml 2>/dev/null; then
    echo "dialog not open, retry tap"
    "$ADB" -s $DEV shell input tap 280 1667 2>&1
    sleep 2
  fi
  # UUID
  "$ADB" -s $DEV shell input tap 500 637 2>&1
  sleep 1
  "$ADB" -s $DEV shell input text "$UUID" 2>&1
  sleep 1
  # 勾属性（键盘开着，属性在键盘上方可见）
  for p in $PROPS; do
    case $p in
      READ) "$ADB" -s $DEV shell input tap 230 948 2>&1 ;;
      WRITE_NR) "$ADB" -s $DEV shell input tap 385 1083 2>&1 ;;
      WRITE) "$ADB" -s $DEV shell input tap 230 1218 2>&1 ;;
      NOTIFY) "$ADB" -s $DEV shell input tap 330 1353 2>&1 ;;
      INDICATE) "$ADB" -s $DEV shell input tap 255 1488 2>&1 ;;
    esac
    sleep 1
  done
  # 收键盘
  "$ADB" -s $DEV shell input keyevent 4 2>&1
  sleep 1
  # 找 OK
  "$ADB" -s $DEV shell uiautomator dump /sdcard/ok.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/ok.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ok.xml 2>&1 >/dev/null
  OKY=$(python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/ok.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'text=\"OK\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
print((int(m.group(2))+int(m.group(4)))//2 if m else 0)
")
  echo "OK y=$OKY"
  [ "$OKY" != "0" ] && "$ADB" -s $DEV shell input tap 884 $OKY 2>&1
  sleep 2
  # 确认添加成功
  "$ADB" -s $DEV shell uiautomator dump /sdcard/ck.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/ck.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml 2>&1 >/dev/null
  grep -oE "UUID: 0x$UUID" /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml | head -1
}

add_char C300 "READ"
add_char C301 "READ"
add_char C302 "WRITE"
add_char C303 "WRITE_NR"
add_char C305 "NOTIFY"
add_char C306 "INDICATE"
add_char C307 "READ"
echo "=== ALL DONE ==="
