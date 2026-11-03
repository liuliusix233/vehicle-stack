#!/bin/bash
# 8T 装 ADBKeyboard
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV="${DEV:-192.0.2.10:39777}"   # 改成车机 IP:端口
echo "=== install ==="
"$ADB" -s $DEV install -r /home/<USER>/.openclaw/workspace/jarvis/tmp/adbkeyboard.apk 2>&1 | tail -2
echo "=== enable + set ime ==="
"$ADB" -s $DEV shell ime enable com.android.adbkeyboard/.AdbIME 2>&1
"$ADB" -s $DEV shell ime set com.android.adbkeyboard/.AdbIME 2>&1
"$ADB" -s $DEV shell ime list -s 2>&1 | head
echo "=== test input ==="
"$ADB" -s $DEV shell am broadcast -a ADB_INPUT_TEXT --es msg 'TEST' 2>&1 | tail -1
echo "=== done ==="
