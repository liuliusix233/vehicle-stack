#!/usr/bin/env bash
# Pi 上启动 HUD：瓦片服务 + 数据桥（借 :8077 的 OBD 数据）+ 全屏 Chromium
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${HUD_PORT:-8477}"
SRC="${HUD_SRC:-http://127.0.0.1:8077/}"
ARGS="${HUD_BRIDGE_ARGS:-}"

pkill -f "tile_server.py" 2>/dev/null || true
pkill -f "pi/bridge.py" 2>/dev/null || true

python3 "$DIR/tile_server.py" "$PORT" >/tmp/hud-tiles.log 2>&1 &
python3 "$DIR/pi/bridge.py" --src "$SRC" --out "$DIR/state.json" $ARGS >/tmp/hud-bridge.log 2>&1 &
sleep 1.2

BROWSER="${HUD_BROWSER:-chromium-browser}"
command -v "$BROWSER" >/dev/null || BROWSER=chromium

exec "$BROWSER" \
  --kiosk --noerrdialogs --disable-infobars --disable-session-crashed-bubble \
  --autoplay-policy=no-user-gesture-required --check-for-update-interval=31536000 \
  --window-size=960,240 \
  --app="http://127.0.0.1:$PORT/index.html?bare=1"
