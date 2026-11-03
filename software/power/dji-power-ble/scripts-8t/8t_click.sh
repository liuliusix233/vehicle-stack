#!/bin/bash
# dump clickable 节点
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
python3 << 'EOF'
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/g6.xml', encoding='utf-8', errors='ignore').read()
print('=== clickable nodes ===')
for m in re.finditer(r'<node[^>]*?clickable="true"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1,y1,x2,y2 = m.groups()
    cx, cy = (int(x1)+int(x2))//2, (int(y1)+int(y2))//2
    # 找 content-desc
    cd = re.search(r'content-desc="([^"]*)"', m.group(0))
    label = cd.group(1) if cd else ''
    print(f'CLICK ({cx},{cy}) size={int(x2)-int(x1)}x{int(y2)-int(y1)} desc={label!r}')
EOF
