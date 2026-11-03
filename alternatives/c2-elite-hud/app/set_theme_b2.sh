#!/bin/bash
# set_theme_b2.sh — 循环按键直到 leveldb 确认 theme=b，再截图
set -e
S="adb -s <LAN_IP>:5555"

get_theme() {
  $S shell "su -c 'cat \"/data/data/com.vox.hud/app_webview/Local Storage/leveldb/\"*.log 2>/dev/null | tail -c 400'" 2>/dev/null \
    | grep -a -o 'hud_theme_v2.\{0,4\}' | tail -1 | grep -a -o '[ab]$' || echo "?"
}

for i in 1 2 3 4; do
  V=$(get_theme)
  echo "check $i: theme=$V"
  if [ "$V" = "b" ]; then break; fi
  $S shell input keyevent 22
  sleep 2
done

FINAL=$(get_theme)
echo "FINAL=$FINAL"
$S shell screencap -p /sdcard/tb2.png
$S pull /sdcard/tb2.png /tmp/tb2.png
ls -la /tmp/tb2.png
echo SETB2_DONE
