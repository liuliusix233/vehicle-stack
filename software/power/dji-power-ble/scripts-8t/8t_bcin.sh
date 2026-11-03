#!/bin/bash
# broadcast 输入 C305 测试
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
# 先聚焦 UUID 框（当前对话框开着）
"$ADB" -s $DEV shell input tap 500 637 2>&1
sleep 1
"$ADB" -s $DEV shell am broadcast -a ADB_INPUT_TEXT --es msg 'C305' 2>&1 | tail -1
sleep 2
"$ADB" -s $DEV shell uiautomator dump /sdcard/t1.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/t1.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/t1.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/t1.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'class=\"android.widget.EditText\"[^>]*?text=\"([^\"]*)\"', xml):
    print('EditText:', repr(m.group(1)))
"
