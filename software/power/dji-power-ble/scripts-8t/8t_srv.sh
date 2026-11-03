#!/bin/bash
# 进 Server 页截图看启用按钮
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell input tap 72 178 2>&1
sleep 2
"$ADB" -s $DEV shell input tap 394 650 2>&1
sleep 2
"$ADB" -s $DEV shell screencap -p /sdcard/srv.png 2>&1
"$ADB" -s $DEV pull /sdcard/srv.png /home/<USER>/.openclaw/workspace/jarvis/tmp/srv.png 2>&1 | tail -1
"$ADB" -s $DEV shell uiautomator dump /sdcard/srv.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/srv.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/srv.xml 2>&1 >/dev/null
python3 << 'EOF'
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/srv.xml', encoding='utf-8', errors='ignore').read()
print('=== text nodes (top 15) ===')
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
print('=== clickable nodes ===')
for m in re.finditer(r'<node[^>]*?clickable="true"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1,y1,x2,y2 = m.groups()
    cx, cy = (int(x1)+int(x2))//2, (int(y1)+int(y2))//2
    cd = re.search(r'content-desc="([^"]*)"', m.group(0))
    print(f'CLICK ({cx},{cy}) desc={cd.group(1) if cd else ""!r}')
EOF
