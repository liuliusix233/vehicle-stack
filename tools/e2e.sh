#!/usr/bin/env bash
# 端到端验收：5 项 PASS 才算完成
set -uo pipefail
[ -f .env ] && set -a && . ./.env && set +a
pass=0; total=5
check() { if eval "$2" >/dev/null 2>&1; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fi; }
check "car-reachable" "ping -c1 -W2 ${CAR_HOST:-127.0.0.1}"
check "shellbridge-ping" "curl -sf -m4 http://${CAR_HOST:-127.0.0.1}:${CAR_SHELL_PORT:-7801}/ping"
check "pi-reachable" "nc -z -w3 ${PI_HOST:-127.0.0.1} ${RELAY_PORT:-7801}"
check "hud-process" "ssh ${PI_USER:-pi}@${PI_HOST:-127.0.0.1} pgrep -f fb_dash.py"
check "ha-api" "curl -sf -m5 -H 'Authorization: Bearer ${HA_TOKEN}' ${HA_URL}/api/"
echo "$pass/$total passed"
[ $pass -eq $total ] || exit 1