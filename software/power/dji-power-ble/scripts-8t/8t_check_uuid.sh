#!/bin/bash
# 确认新服务 UUID + 添加特征
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/ok1.xml', encoding='utf-8', errors='ignore').read()
# 找 Unknown Service 的 UUID
idx = xml.find('Unknown Service')
seg = xml[idx:idx+2000]
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', seg):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
"
