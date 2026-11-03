#!/bin/bash
# auto-rescue.sh — 部署到 Pi，直到 HUD 上线后清理坏文件
# 用法: nohup bash /tmp/auto-rescue.sh > /tmp/auto-rescue.log 2>&1 &

HUD_IPS="140 141 142 143 144 145 146 147 148 149 150"

while true; do
  for ip in $HUD_IPS; do
    if ping -c 1 -W 1 192.168.0.$ip >/dev/null 2>&1; then
      echo "[$(date +%H:%M:%S)] Found 192.168.0.$ip, trying ADB..."
      adb connect 192.168.0.$ip:5555 >/dev/null 2>&1
      sleep 2
      STATE=$(adb devices 2>/dev/null | grep "192.168.0.$ip:5555" | awk '{print $2}')
      if [ "$STATE" = "device" ]; then
        echo "[$(date +%H:%M:%S)] ADB connected to $ip! Removing bad files..."
        adb -s 192.168.0.$ip:5555 shell "su -c 'rm -f /data/adb/service.d/auto_brightness.sh /data/local/tmp/auto_brightness.sh && echo REMOVED_OK'"
        echo "[$(date +%H:%M:%S)] DONE - bad files removed!"
        exit 0
      fi
    fi
  done
  sleep 2
done
