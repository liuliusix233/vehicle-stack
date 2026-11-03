#!/bin/bash
# 裁剪 A002 行区域看图标
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
"$ADB" -s $DEV shell screencap -p /sdcard/a002row.png 2>&1
"$ADB" -s $DEV pull /sdcard/a002row.png /home/<USER>/.openclaw/workspace/jarvis/tmp/a002row.png 2>&1 | tail -1
python3 -c "
from PIL import Image
img = Image.open('/home/<USER>/.openclaw/workspace/jarvis/tmp/a002row.png')
print('size', img.size)
crop = img.crop((0, 1100, 1080, 1350))
crop = crop.resize((crop.width*2, crop.height*2), Image.LANCZOS)
crop.save('/home/<USER>/.openclaw/workspace/jarvis/tmp/a002row_zoom.png')
"
