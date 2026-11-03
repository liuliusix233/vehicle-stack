#!/system/bin/sh
# HUD ttyMT0 串口抓取脚本 — 方案A：直连 OBD
# 用法: sh /data/local/tmp/sniff_obd.sh <port> <baud>
# 例:   sh /data/local/tmp/sniff_obd.sh /dev/ttyMT0 115200
# 输出: /data/local/tmp/obd_capture_<baud>.bin (原始字节)
#       同时把帧按 0x7E 分帧打印到 logcat tag=OBDRAW

PORT=${1:-/dev/ttyMT0}
BAUD=${2:-115200}
OUT=/data/local/tmp/obd_capture_${BAUD}.bin

busybox stty -F $PORT raw -echo $BAUD 2>/dev/null
if [ $? -ne 0 ]; then
  echo "stty failed on $PORT @ $BAUD"
  exit 1
fi

echo "sniffing $PORT @ $BAUD -> $OUT"
# 连续抓 60 秒原始字节
timeout 60 busybox cat $PORT > $OUT 2>/dev/null
echo "done, size:"
busybox wc -c $OUT
# hexdump 前几行
busybox hexdump -C $OUT 2>/dev/null | head -40
