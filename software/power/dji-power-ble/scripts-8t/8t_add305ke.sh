#!/bin/bash
# 补加 C305 (Notify) - keyevent 输入
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口

echo "=== cancel current dlg ==="
"$ADB" -s $DEV shell input tap 687 1424 2>&1
sleep 2

add_char_ke() {
  local UUIDKEYS=$1 PROPS=$2
  echo "=== add ($UUIDKEYS) ==="
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
  # UUID field
  "$ADB" -s $DEV shell input tap 500 637 2>&1
  sleep 1
  # keyevent input
  for k in $UUIDKEYS; do
    "$ADB" -s $DEV shell input keyevent $k 2>&1
  done
  sleep 1
  # verify
  "$ADB" -s $DEV shell uiautomator dump /sdcard/u.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/u.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/u.xml 2>&1 >/dev/null
  UVAL=$(python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/u.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
# 找 UUID 行下的 EditText
for mm in re.finditer(r'class=\"android.widget.EditText\"[^>]*?text=\"([^\"]*)\"', xml):
    print(repr(mm.group(1)))
" 2>/dev/null | head -3)
  echo "UUID field: $UVAL"
  # props
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
  # 处理 2902 descriptor 对话框（如果有）
  "$ADB" -s $DEV shell uiautomator dump /sdcard/d2.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/d2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/d2.xml 2>&1 >/dev/null
  if grep -q "Add characteristic" /home/<USER>/.openclaw/workspace/jarvis/tmp/d2.xml 2>/dev/null; then
    read OKX2 OKY2 <<< $(python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/d2.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'text=\"OK\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
if m:
    print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)
else:
    print('0 0')
")
    echo "dlg still open, OK2 at ($OKX2,$OKY2)"
    [ "$OKX2" != "0" ] && "$ADB" -s $DEV shell input tap $OKX2 $OKY2 2>&1
    sleep 2
  fi
  # check
  "$ADB" -s $DEV shell uiautomator dump /sdcard/ck.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/ck.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml 2>&1 >/dev/null
  grep -oE "UUID: 0xC30[56]|Properties: [^\"]*" /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml | head -4
}

# C=31, 3=8, 0=7, 5=9 | C306: C=31 3=8 0=7 6=10
add_char_ke "31 8 7 9" "Notify"
add_char_ke "31 8 7 10" "Indicate"
echo "=== DONE ==="
