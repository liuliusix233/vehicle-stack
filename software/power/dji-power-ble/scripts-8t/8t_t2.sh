#!/bin/bash
# 截图 + VLM
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell screencap -p /sdcard/t2.png 2>&1
"$ADB" -s $DEV pull /sdcard/t2.png /home/<USER>/.openclaw/workspace/jarvis/tmp/t2.png 2>&1 | tail -1
cd /home/<USER>/.openclaw/workspace/jarvis
python3 tmp/vlm_ask.py /home/<USER>/.openclaw/workspace/jarvis/tmp/t2.png "这是 Add characteristic 对话框。UUID 输入框里现在是什么内容？完整读出。键盘状态？" 2>&1 | head -10
