#!/system/bin/sh
# ============================================================
# HUD Auto Brightness v4 — Magisk late_start service
# ============================================================
# Place in /data/adb/service.d/auto_brightness.sh on the HUD
# Starts auto brightness daemon after boot
# ============================================================

# Kill existing instances
killall auto_brightness.sh 2>/dev/null

# Wait for system ready
sleep 15

# Wait for ALS sensor available
for i in $(seq 1 20); do
    if [ -r /sys/bus/platform/drivers/als_ps/als ]; then
        break
    fi
    sleep 1
done

# Start brightness daemon
nohup /data/local/tmp/auto_brightness.sh >/dev/null 2>&1 &
