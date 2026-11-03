#!/bin/bash
# 找 Custom service 可点元素
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
python3 << 'EOF'
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/a1.xml', encoding='utf-8', errors='ignore').read()
print('=== all nodes with Custom/switch/radio/checkbox ===')
for m in re.finditer(r'<node[^>]*?(?:text="([^"]*)"|content-desc="([^"]*)")[^>]*?class="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?(?:checked="([^"]*)")?[^>]*?clickable="([^"]*)"', xml):
    t, cd, cls, x1, y1, x2, y2, chk, clk = m.groups()
    if t or cd:
        label = t or cd
        if any(k in label.lower() for k in ['custom', 'service', 'switch']):
            print(f'{label!r} [{cls.split(".")[-1]}] at ({x1},{y1})-({x2},{y2}) checked={chk} clickable={clk}')
EOF
