#!/bin/bash
# set_theme_b.sh — 切到 Theme B 并验证
set -e
S="adb -s <LAN_IP>:5555"

# 先读当前主题
CUR=$($S shell "su -c 'cat \"/data/data/com.vox.hud/app_webview/Local Storage/leveldb/\"*.log 2>/dev/null | tail -c 300'" | grep -a -o 'hud_theme_v2.\{0,4\}' | tail -1 || true)
echo "CUR_RAW=$CUR"

# 按右移切换
$S shell input keyevent 22
sleep 2

# 验证写入值
NEW=$($S shell "su -c 'cat \"/data/data/com.vox.hud/app_webview/Local Storage/leveldb/\"*.log 2>/dev/null | tail -c 300'" | grep -a -o 'hud_theme_v2.\{0,4\}' | tail -1 || true)
echo "NEW_RAW=$NEW"

# 截图
$S shell screencap -p /sdcard/tb.png
$S pull /sdcard/tb.png /tmp/tb.png
ls -la /tmp/tb.png
echo SETB_DONE
