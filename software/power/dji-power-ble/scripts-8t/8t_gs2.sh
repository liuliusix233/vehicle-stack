#!/bin/bash
# 进 GATT Server + 选配置 + 启用
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== menu ==="
"$ADB" -s $DEV shell input tap 72 178 2>&1
sleep 2
"$ADB" -s $DEV shell input tap 394 650 2>&1
sleep 2
echo "=== current gatt server state ==="
"$ADB" -s $DEV shell uiautomator dump /sdcard/gs2.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/gs2.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/gs2.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/gs2.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -10
