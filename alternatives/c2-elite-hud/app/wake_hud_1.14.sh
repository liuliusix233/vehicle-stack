#!/bin/bash
# wake_hud.sh — Pi 上执行: 唤醒 HUD 屏幕 → 保持常亮 → 确保 app 前台 → Theme B → 截图
set -e
HUD_IP=<LAN_IP>
S="adb -s $HUD_IP:5555"

echo "== wake =="
$S shell input keyevent 224   # WAKEUP
sleep 1
$S shell input keyevent 82    # MENU unlock
sleep 1
$S shell svc power stayon true
echo "== foreground =="
$S shell am start -n com.vox.hud/.MainActivity
sleep 5
echo "== 切 Theme B =="
$S shell input keyevent 22
sleep 2
echo "== screencap =="
$S shell screencap -p /sdcard/hud114b.png
$S pull /sdcard/hud114b.png /tmp/hud114b.png
ls -la /tmp/hud114b.png
echo "WAKE_DONE"
