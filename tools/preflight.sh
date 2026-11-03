#!/usr/bin/env bash
# 环境自检：缺什么就打印缺什么，最后一行 PREFLIGHT OK 才算通过
set -uo pipefail
fail=0
need() { command -v "$1" >/dev/null 2>&1 && echo "  ok   $1" || { echo "  MISS $1"; fail=1; }; }
echo "[toolchain]"
need java; need python3; need ffmpeg; need git
command -v adb >/dev/null 2>&1 && echo "  ok   adb" || echo "  WARN adb 缺失（可选：车机装 APK 走 U 盘）"
echo "[python deps]"
python3 -c 'import PIL; print("  ok   Pillow", PIL.__version__)' 2>/dev/null || { echo "  MISS Pillow"; fail=1; }
echo "[android sdk]"
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/platforms" ]; then echo "  ok   ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"; else echo "  WARN ANDROID_SDK_ROOT 未设置（构建 APK 需要）"; fi
echo "[env]"
[ -f .env ] && echo "  ok   .env" || echo "  WARN 缺少 .env（cp .env.example .env）"
echo "[repo]"
python3 tools/sanitize.py --root . --out /dev/null --quiet && echo "  ok   sanitize: 无 HIGH" || { echo "  FAIL sanitize 发现 HIGH"; fail=1; }
[ $fail -eq 0 ] && echo "PREFLIGHT OK" || echo "PREFLIGHT FAILED"
exit $fail