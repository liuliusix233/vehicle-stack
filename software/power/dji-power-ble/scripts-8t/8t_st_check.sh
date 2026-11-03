#!/bin/bash
# 看 8T 状态
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
"$ADB" -s $DEV shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1
"$ADB" -s $DEV shell screencap -p /sdcard/8t_st.png 2>&1
"$ADB" -s $DEV pull /sdcard/8t_st.png /home/<USER>/.openclaw/workspace/jarvis/tmp/8t_st.png 2>&1 | tail -1
"$ADB" -s $DEV shell uiautomator dump /sdcard/8t_st.xml 2>&1 >/dev/null
"$ADB" -s $DEV pull /sdcard/8t_st.xml /home/<USER>/.openclaw/workspace/jarvis/tmp/8t_st.xml 2>&1 >/dev/null
python3 -c "
import re
xml = open('/home/<USER>/.openclaw/workspace/jarvis/tmp/8t_st.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip():
        print(f'{t!r} at ({x1},{y1})-({x2},{y2})')
" | head -15
