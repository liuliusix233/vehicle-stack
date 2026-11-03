#!/bin/bash
# Pi-side emergency watchdog
# Waits for HUD to come online, then removes bad boot script
# Deploy: scp to pi, run with: bash rescue_hud.sh &

HUD_IPS="120 121 122 123 124 125 130 131 132 133 134 135 140 141 142 143 144 145 150 151 152"

echo "[$$] Rescue watchdog started, scanning every 3s..."
while true; do
  for ip in $HUD_IPS; do
    if ping -c 1 -W 1 192.168.0.$ip 2>/dev/null | grep -q "1 received"; then
      echo "[$$] Found 192.168.0.$ip, trying ADB..."
      adb connect 192.168.0.$ip:5555 2>/dev/null
      sleep 2
      if adb devices 2>/dev/null | grep -q "192.168.0.$ip:5555.*device"; then
        echo "[$$] ADB CONNECTED! Removing bad script..."
        adb -s 192.168.0.$ip:5555 shell "su -c 'rm -f /data/adb/service.d/auto_brightness.sh /data/local/tmp/auto_brightness.sh && echo REMOVED_OK'"
        echo "[$$] DONE - bad files removed"
        exit 0
      fi
    fi
  done
  sleep 3
done
