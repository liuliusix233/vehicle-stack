#!/system/bin/sh
# ============================================================
# HUD WiFi Auto-Connect — Magisk late_start service
# ============================================================
# Place in /data/adb/service.d/ on the HUD
# Enables WiFi on boot and starts ADB over TCP
# ============================================================

# Enable WiFi
svc wifi enable

# Wait for WiFi to connect (check up to 30 seconds)
for i in $(seq 1 30); do
    IFACE=$(ip route show default 2>/dev/null | head -1)
    if [ -n "$IFACE" ]; then
        break
    fi
    sleep 1
done

# Start ADB over TCP (keep it running)
setprop service.adb.tcp.port 5555
stop adbd
sleep 1
start adbd

# Log to kernel log
log -t HudWiFi "WiFi enabled, ADB TCP started"
