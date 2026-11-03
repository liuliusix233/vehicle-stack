#!/bin/bash
# 找 OK + 确认 Connectable
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
python3 << 'EOF'
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/conn.xml', encoding='utf-8', errors='ignore').read()
print('=== all visible ===')
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
# checkbox states
print('=== checkbox/switch states ===')
for m in re.finditer(r'<node[^>]*?class="android.widget.CheckBox"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?checked="([^"]*)"', xml):
    x1,y1,x2,y2,chk = m.groups()
    print(f'CheckBox at ({x1},{y1})-({x2},{y2}) checked={chk}')
EOF
