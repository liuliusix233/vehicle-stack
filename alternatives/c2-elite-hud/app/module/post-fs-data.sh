#!/system/bin/sh
# ADB-Restore — 开机恢复ADB免密调试
# 在 post-fs-data 阶段执行（Magisk触发）

# 1. 设置ADB TCP端口
setprop service.adb.tcp.port 5555

# 2. 关闭RSA密钥认证
setprop service.adb.secure 0
setprop persist.adb.secure 0

# 3. 如果TF卡（/storage/XXXX-XXXX）上有adb_keys，自动导入
for sd in /storage/*; do
  if [ -f "$sd/adb_keys" ]; then
    mkdir -p /data/misc/adb
    cp "$sd/adb_keys" /data/misc/adb/adb_keys
    chmod 640 /data/misc/adb/adb_keys
    chown system:shell /data/misc/adb/adb_keys
    log -t ADB-Restore "Imported adb_keys from $sd"
    break
  fi
done

# 4. 重启ADBD（需要root，Magisk环境可执行）
magiskpolicy --live "allow adbd adbd process setcurrent" 2>/dev/null
stop adbd 2>/dev/null
start adbd 2>/dev/null

log -t ADB-Restore "ADB insecure mode restored, port=5555"
