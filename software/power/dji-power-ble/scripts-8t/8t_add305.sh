#!/bin/bash
# 补加 C305/C306（处理 CCCD 确认）
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777

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
  "$ADB" -s $DEV shell input tap $AX $AY 2>&1
  sleep 2
  "$ADB" -s $DEV shell input tap 500 637 2>&1
  sleep 1
  "$ADB" -s $DEV shell input text "$UUID" 2>&1
  sleep 1
  for p in $PROPS; do
    case $p in
      Notify) "$ADB" -s $DEV shell input tap 330 1353 2>&1 ;;
      Indicate) "$ADB" -s $DEV shell input tap 255 1488 2>&1 ;;
    esac
    sleep 1
  done
  # OK
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
  # 处理可能弹出的 Add descriptor 对话框（接受 = 点 OK）
  "$ADB" -s $DEV shell uiautomator dump /sdcard/d2.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/d2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/d2.xml 2>&1 >/dev/null
  python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/d2.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip() and ('descriptor' in t.lower() or 'OK' in t or 'Add' in t or '2902' in t or 'CCCD' in t):
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -8
  # 如果出现 descriptor 对话框，点 OK
  read DX DY <<< $(python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/d2.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'text=\"OK\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
if m:
    print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)
else:
    print('0 0')
")
  if [ -n "$DX" ] && [ "$DX" != "0" ]; then
    echo "second dialog OK at ($DX,$DY)"
    "$ADB" -s $DEV shell input tap $DX $DY 2>&1
    sleep 2
  fi
  "$ADB" -s $DEV shell uiautomator dump /sdcard/ck2.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/ck2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ck2.xml 2>&1 >/dev/null
  grep -oE "UUID: 0x$UUID" /home/<USER>/.openclaw/workspace/jarvis/tmp/ck2.xml | head -1
}

add_char C305 "Notify"
add_char C306 "Indicate"
echo "=== DONE ==="
