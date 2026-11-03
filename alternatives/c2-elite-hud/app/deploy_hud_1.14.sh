#!/bin/bash
# deploy_hud.sh — Pi 上执行: 找 HUD → adb install → 启动 → 切 Theme B → 截图
set -e
APK=/tmp/HudDashboard-1.14.apk

echo "== nmap 找 HUD (5555) =="
HUD_IP=""
while IFS= read -r line; do
  ip=$(echo "$line" | grep -oP '\d+\.\d+\.\d+\.\d+' || true)
  if [ -n "$ip" ]; then HUD_IP="$ip"; break; fi
done < <(nmap -p 5555 --open -T4 <LAN_IP>/24 2>/dev/null | grep "Nmap scan report")

if [ -z "$HUD_IP" ]; then echo "NO_HUD_FOUND"; exit 1; fi
echo "HUD_IP=$HUD_IP"

adb connect "$HUD_IP:5555"
sleep 2
adb devices

echo "== install =="
adb -s "$HUD_IP:5555" install -r "$APK"

echo "== start app =="
adb -s "$HUD_IP:5555" shell am start -n com.vox.hud/.MainActivity
sleep 4

echo "== 切 Theme B (DPAD_RIGHT) =="
adb -s "$HUD_IP:5555" shell input keyevent 22
sleep 2

echo "== screencap =="
adb -s "$HUD_IP:5555" shell screencap -p /sdcard/hud114.png
adb -s "$HUD_IP:5555" pull /sdcard/hud114.png /tmp/hud114.png
ls -la /tmp/hud114.png
echo "DEPLOY_DONE"
