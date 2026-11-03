#!/bin/bash
# 继续添加特征（滚动+动态定位）
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777

add_char() {
  local UUID=$1 PROPS=$2
  echo "=== add $UUID ($PROPS) ==="
  # 滚动到底部（最多3次）
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
    if [ -n "$AX" ] && [ "$AX" != "0" ]; then break; fi
    "$ADB" -s $DEV shell input swipe 540 2200 540 900 400 2>&1
    sleep 1
  done
  if [ -z "$AX" ] || [ "$AX" = "0" ]; then echo "AC not found after scroll"; return; fi
  echo "AC at ($AX,$AY)"
  "$ADB" -s $DEV shell input tap $AX $AY 2>&1
  sleep 2
  "$ADB" -s $DEV shell uiautomator dump /sdcard/cd.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/cd.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/cd.xml 2>&1 >/dev/null
  if ! grep -q "Add characteristic" /home/<USER>/.openclaw/workspace/jarvis/tmp/cd.xml 2>/dev/null; then echo "no dlg"; return; fi
  # UUID
  read UX UY <<< $(python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/cd.xml', encoding='utf-8', errors='ignore').read()
m = re.search(r'text=\"UUID\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
if m:
    x1,y1,x2,y2 = map(int, m.groups())
    print((x1+x2)//2, (y1+y2)//2 + 55)
else:
    print('500 637')
")
  "$ADB" -s $DEV shell input tap $UX $UY 2>&1
  sleep 1
  "$ADB" -s $DEV shell input text "$UUID" 2>&1
  sleep 1
  # properties from cd.xml
  for p in $PROPS; do
    read PX PY <<< $(python3 -c "
import re, sys
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/cd.xml', encoding='utf-8', errors='ignore').read()
target = sys.argv[1]
m = re.search(r'text=\"' + target + r'\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
if m:
    x1,y1,x2,y2 = map(int, m.groups())
    print((x1+x2)//2, (y1+y2)//2)
else:
    print('0 0')
" "$p")
    [ "$PX" != "0" ] && { "$ADB" -s $DEV shell input tap $PX $PY 2>&1; sleep 1; }
  done
  # OK (keyboard should close after prop taps)
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
  "$ADB" -s $DEV shell uiautomator dump /sdcard/ck.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/ck.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml 2>&1 >/dev/null
  grep -oE "UUID: 0x$UUID" /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml | head -1
}

add_char C303 "Write without response"
add_char C305 "Notify"
add_char C306 "Indicate"
add_char C307 "Read"
echo "=== ALL DONE ==="
