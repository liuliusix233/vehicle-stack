#!/system/bin/sh
# Magisk service.d — Tailscale 开机自启 (v2)
# 放置在 /data/adb/service.d/99-tailscale-autostart.sh
# chmod 755 /data/adb/service.d/99-tailscale-autostart.sh
#
# C2-Elite HUD 内核不支持普通 TUN 路由，必须用 userspace-networking

LOG_TAG="Tailscale"
log -t "$LOG_TAG" "Auto-start triggered"

sleep 10

TAILSCALED="/data/local/tmp/tailscaled"
TAILSCALE="/data/local/tmp/tailscale"
STATE_DIR="/data/local/tmp/ts-state"
STATE_FILE="$STATE_DIR/tailscaled.state"
SOCKET="$STATE_DIR/tailscaled.sock"

if [ ! -f "$TAILSCALED" ] || [ ! -f "$TAILSCALE" ]; then
    log -t "$LOG_TAG" "Binary not found"
    exit 0
fi

mkdir -p "$STATE_DIR"

# 检查是否已在运行
if [ -S "$SOCKET" ]; then
    STATUS=$($TAILSCALE --socket="$SOCKET" status 2>/dev/null | grep -c "100\.")
    if [ "$STATUS" -gt 0 ]; then
        log -t "$LOG_TAG" "Already running"
        exit 0
    fi
fi

# 清理旧进程
killall -9 tailscaled 2>/dev/null; sleep 1; rm -f "$SOCKET"

# 启动 (必须 userspace-networking, HUD 不支持 TUN 路由表)
nohup "$TAILSCALED" \
    --state="$STATE_FILE" \
    --socket="$SOCKET" \
    --tun=userspace-networking \
    > "$STATE_DIR/tsd.log" 2>&1 &
sleep 5

# 检查状态
if [ -S "$SOCKET" ]; then
    log -t "$LOG_TAG" "Tailscaled started"
    $TAILSCALE --socket="$SOCKET" up --accept-routes=false 2>/dev/null
    IP=$($TAILSCALE --socket="$SOCKET" ip 2>/dev/null)
    log -t "$LOG_TAG" "IP: $IP"
else
    log -t "$LOG_TAG" "FAILED to start"
fi

exit 0
