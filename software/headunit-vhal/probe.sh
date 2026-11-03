#!/usr/bin/env bash
# 车机 VHAL 探测：./probe.sh CARALL | PROP <id> <area> | SUBS <id> <secs> | SUBSALL <secs>
set -uo pipefail
[ -f .env ] && set -a && . ./.env && set +a
HOST=${CAR_HOST:-192.0.2.50}
PORT=${CAR_SHELL_PORT:-7801}
case ${1:-CARALL} in
  CARALL) cmd='#CARALL' ;;
  PROP)   cmd="#PROP ${2:-0x11400400} ${3:-0}" ;;
  SUBS)   cmd="#SUBS ${2:-0x11400401} ${3:-12}" ;;
  SUBSALL) cmd="#SUBSALL ${2:-12}" ;;
  RPM)    cmd='#RPM' ;;
  VSTATE) cmd='#VSTATE' ;;
  *) echo "unknown: $1"; exit 1 ;;
esac
echo "-> $cmd  @ $HOST:$PORT"
printf '%s\n' "$cmd" | nc -w 20 "$HOST" "$PORT"