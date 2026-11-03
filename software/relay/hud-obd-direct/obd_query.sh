#!/system/bin/sh
# HUD ttyMT0 OBD 请求-响应验证脚本（车启动时运行）
# 原理: OBD 是请求-响应模式, 需主动发请求帧才有数据 (逆向自 aicore SerialService)
# 用法: sh /data/local/tmp/obd_query.sh [baud] [seconds]
# 默认: 19200 波特率, 30 秒

BAUD=${1:-19200}
SECS=${2:-30}
PORT=/dev/ttyMT0
OUT=/data/local/tmp/obd_resp_${BAUD}.bin

echo "=== OBD query test @ ${BAUD} ==="
/system/bin/busybox stty -F $PORT raw -echo $BAUD 2>&1
echo "stty done, capturing ${SECS}s..."

# 后台读串口
timeout $SECS /system/bin/busybox cat $PORT > $OUT 2>/dev/null &
READER=$!
sleep 1

# 前台每 1 秒发请求帧 (模拟 SerialService Timer 1s 周期)
# 帧: 7E 8004 0006 0001 0000 000101000300 80 7E (来自 aicore 日志)
CNT=0
while [ $CNT -lt $SECS ]; do
  # count 递增 (2 bytes big-endian)
  C1=$(( (CNT >> 8) & 0xFF ))
  C2=$(( CNT & 0xFF ))
  C1H=$(printf '%02x' $C1)
  C2H=$(printf '%02x' $C2)
  # 重算校验: xor of 80 04 00 06 C1 C2 00 00 00 01 01 00 03 00
  CS=$(( 0x80 ^ 0x04 ^ 0x00 ^ 0x06 ^ C1 ^ C2 ^ 0x00 ^ 0x00 ^ 0x00 ^ 0x01 ^ 0x01 ^ 0x00 ^ 0x03 ^ 0x00 ))
  CSH=$(printf '%02x' $CS)
  echo -n -e "\x7e\x80\x04\x00\x06\x${C1H}\x${C2H}\x00\x00\x00\x01\x01\x00\x03\x00\x${CSH}\x7e" > $PORT 2>/dev/null
  sleep 1
  CNT=$((CNT + 1))
done

wait $READER 2>/dev/null
echo "=== done, size: ==="
/system/bin/busybox wc -c $OUT
echo "=== hexdump ==="
/system/bin/busybox hexdump -C $OUT 2>/dev/null | head -60
