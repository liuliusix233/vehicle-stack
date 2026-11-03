#!/usr/bin/env bash
# DIYHUD 一键安装并投屏
# Pi 上执行：curl -fsSL http://<TS_IP>:8477/install.sh | bash
set -euo pipefail

SRC="${HUD_SRC:-http://127.0.0.1:8077/}"
DEST="${HUD_DIR:-$HOME/diyhud}"
CANDS=("${HUD_BASE:-}" "http://<TS_IP>:8477" "http://<LAN_IP>:8477")

ok=0
for B in "${CANDS[@]}"; do
  [ -z "$B" ] && continue
  echo "==> 尝试 $B"
  if curl -fsS -m 6 -o /tmp/diyhud.tar.gz "$B/diyhud.tar.gz"; then
    echo "==> 下载成功"
    ok=1
    break
  fi
done
[ "$ok" = 1 ] || { echo "下载失败：SER 的 8477 不可达"; exit 1; }

mkdir -p "$(dirname "$DEST")"
tar xzf /tmp/diyhud.tar.gz -C "$(dirname "$DEST")"
cd "$DEST"
chmod +x pi/kiosk.sh pi/bridge.py

if ! command -v chromium-browser >/dev/null 2>&1 && ! command -v chromium >/dev/null 2>&1; then
  echo "==> 安装 chromium"
  sudo apt-get update -qq || true
  sudo apt-get install -y chromium-browser || sudo apt-get install -y chromium
fi

echo "==> 启动（OBD 数据源 $SRC）"
HUD_SRC="$SRC" ./pi/kiosk.sh
