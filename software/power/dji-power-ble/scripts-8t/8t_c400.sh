#!/bin/bash
# A003 加 C400/C401 (READ)
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777

add_char() {
  local UUID=$1
  echo "=== add $UUID ==="
  # 定位 ADD CHARACTERISTIC
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
  [ -z "$AX" ] || [ "$AX" = "0" ] && { echo "AC not found"; return; }
  echo "AC at ($AX,$AY)"
  "$ADB" -s $DEV shell input tap $AX $AY 2>&1
  sleep 2
  # UUID
  "$ADB" -s $DEV shell input tap 500 637 2>&1
  sleep 1
  "$ADB" -s $DEV shell input text "$UUID" 2>&1
  sleep 1
  # Read prop
  "$ADB" -s $DEV shell input tap 230 948 2>&1
  sleep 1
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
  sleep 2
  "$ADB" -s $DEV shell uiautomator dump /sdcard/ck.xml 2>&1 >/dev/null
  "$ADB" -s $DEV pull /sdcard/ck.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml 2>&1 >/dev/null
  grep -oE "UUID: 0x$UUID" /home/<USER>/.openclaw/workspace/jarvis/tmp/ck.xml | head -1
}

add_char C400
add_char C401
echo "=== DONE ==="
