#!/bin/bash
# dump 找右下角按钮
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
python3 << 'EOF'
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/adv2.xml', encoding='utf-8', errors='ignore').read()
print('=== clickable nodes ===')
for m in re.finditer(r'<node[^>]*?clickable="true"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1,y1,x2,y2 = m.groups()
    cx, cy = (int(x1)+int(x2))//2, (int(y1)+int(y2))//2
    cd = re.search(r'content-desc="([^"]*)"', m.group(0))
    cls = re.search(r'class="([^"]*)"', m.group(0))
    print(f'CLICK ({cx},{cy}) size={int(x2)-int(x1)}x{int(y2)-int(y1)} desc={cd.group(1) if cd else ""!r} cls={cls.group(1).split(".")[-1] if cls else "?"}')
EOF
