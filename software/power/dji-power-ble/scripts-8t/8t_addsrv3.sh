#!/bin/bash
# 找 ADD SERVICE + 重新添加 A002
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/gs1.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"(ADD SERVICE|ADD CHARACTERISTIC)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    print(f'{t} at ({x1},{y1})-({x2},{y2}) center=({(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2})')
"
