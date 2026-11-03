#!/bin/bash
# 添加 C305/C306/C307 v5
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口

add_char() {
  local UUID=$1 PROPS=$2
  echo "=== add $UUID ($PROPS) ==="
  # 滚动找 AC
  for i in 1 2 3; do
    "$ADB" -s $DEV shell uiautomator dump /sdcard/p.xml 2>&1 >/dev/null
    "$ADB" -s $DEV pull /sdcard/p.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/p.xml 2>&1 >/dev/null
    read AX AY <<< $(python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/p.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'text=\"ADD CHARACTERISTIC\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
if m:
    print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)
else:
    print('0 0')
" 2>/dev/null)
    [ -n "$AX" ] && [ "$AX" != "0" ] && break
    "$ADB" -s $DEV shell input swipe 540 2200 540 900 400 2>&1; sleep 1
  done
  [ -z "$AX" ] || [ "$AX" = "0" ] && { echo "AC not found"; return; }
  echo "AC at ($AX,$AY)"
  "$ADB" -s $DEV shell input tap $AX $AY 2>&1
  sleep 2
  # UUID
  "$ADB" -s $DEV shell input tap 500 637 2>&1
  sleep 1
  "$ADB" -s $DEV shell input text "$UUID" 2>&1
  sleep 1
  # props（固定坐标，键盘开着时属性在键盘上方可见）
  for p in $PROPS; do
    case $p in
      Read) "$ADB" -s $DEV shell input tap 230 948 2>&1 ;;
      "Write without response") "$ADB" -s $DEV shell input tap 385 1083 2>&1 ;;
      Write) "$ADB" -s $DEV shell input tap 230 1218 2>&1 ;;
      Notify) "$ADB" -s $DEV shell input tap 330 1353 2>&1 ;;
      Indicate) "$ADB" -s $DEV shell input tap 255 1488 2>&1 ;;
    esac
    sleep 1
  done
  # dump 找 OK（勾属性后键盘应收起）
  "$ADB" -s $DEV shell uiautomator dump /sdcard/ok.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/ok.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ok.xml 2>&1 >/dev/null
  read OKX OKY <<< $(python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/ok.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'text=\"OK\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
if m:
    print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)
else:
    print('0 0')
")
  echo "OK at ($OKX,$OKY)"
  [ "$OKX" != "0" ] && "$ADB" -s $DEV shell input tap $OKX $OKY 2>&1
  sleep 3
  # 确认对话框关闭 + 特征添加
  "$ADB" -s $DEV shell uiautomator dump /sdcard/ck.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/ck.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml 2>&1 >/dev/null
  if grep -q "Add characteristic" /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml 2>/dev/null; then
    echo "DLG STILL OPEN - cancel"
    "$ADB" -s $DEV shell input tap 687 1424 2>&1
    sleep 2
  fi
  grep -oE "UUID: 0x$UUID" /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml | head -1
}

add_char C305 "Notify"
add_char C306 "Indicate"
add_char C307 "Read"
echo "=== ALL DONE ==="
