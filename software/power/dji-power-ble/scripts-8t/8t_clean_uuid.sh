#!/bin/bash
# 清 UUID 多余字符 + 勾 Notify + OK
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== tap UUID field + delete CRs ==="
"$ADB" -s $DEV shell input tap 500 637 2>&1
sleep 1
# 光标移到末尾 + 删 6 个字符（CR×3 可能是 CRLF）
for i in 1 2 3 4 5 6; do
  "$ADB" -s $DEV shell input keyevent 67 2>&1
done
sleep 1
"$ADB" -s $DEV shell input keyevent 4 2>&1
sleep 1
"$ADB" -s $DEV shell uiautomator dump /sdcard/u1.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/u1.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/u1.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/u1.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -10
