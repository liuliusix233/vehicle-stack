#!/bin/bash
# 8T 装 ADBKeyboard
export PATH=$PATH:$HOME/platform-tools:/usr/local/bin:/usr/bin
ADB=$(command -v adb || echo adb)
DEV=<LAN_IP>:39777
echo "=== install ==="
"$ADB" -s $DEV install -r /home/<USER>/.openclaw/workspace/jarvis/tmp/adbkeyboard.apk 2>&1 | tail -2
echo "=== enable + set ime ==="
"$ADB" -s $DEV shell ime enable com.android.adbkeyboard/.AdbIME 2>&1
"$ADB" -s $DEV shell ime set com.android.adbkeyboard/.AdbIME 2>&1
"$ADB" -s $DEV shell ime list -s 2>&1 | head
echo "=== test input ==="
"$ADB" -s $DEV shell am broadcast -a ADB_INPUT_TEXT --es msg 'TEST' 2>&1 | tail -1
echo "=== done ==="
