#!/bin/bash
# render_local.sh — render an HTML file to PNG with headless Chromium, escaping the
# exec-tool 2GB RLIMIT_AS by running as a transient systemd user unit.
#
# usage: render_local.sh <out.png> <w> <h> <scale> <url> [extra chrome args...]
set -u
OUT="$1"; W="$2"; H="$3"; SCALE="${4:-1}"; URL="$5"; shift 5
CHROME=/home/<USER>/.cache/ms-playwright/chromium-1208/chrome-linux64/chrome
UNIT="hudshot_$$_$(date +%s)"
rm -f "$OUT"
systemd-run --user --unit="$UNIT" --collect --wait --property=Type=oneshot \
  --setenv=HOME=/home/<USER> \
  "$CHROME" --headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage \
  --hide-scrollbars --disable-lcd-text \
  --force-device-scale-factor="$SCALE" --window-size="$W,$H" \
  --virtual-time-budget=4000 \
  --screenshot="$OUT" "$URL" "$@" >/dev/null 2>&1
if [ -s "$OUT" ]; then echo "OK $OUT $(stat -c%s "$OUT")"; else echo "FAIL $OUT"; fi
