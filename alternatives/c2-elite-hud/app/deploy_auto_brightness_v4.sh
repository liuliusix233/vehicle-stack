#!/bin/bash
# ================================================================
# deploy_auto_brightness_v4.sh
# 
# 一键部署 v4 自动亮度到 HUD
# 用法: bash deploy_auto_brightness_v4.sh
# ================================================================
PI_HOST="pi@<TS_IP>"
HUD_IP="<LAN_IP>"  # HUD 实际IP，可能变了

ADB_CMD="adb -s \${HUD_IP}:5555"
PI_SSH="sshpass -p 953261874 ssh $PI_HOST"

echo "=== Step 1: 连接 HUD ==="
$PI_SSH "adb connect $HUD_IP:5555 2>/dev/null; sleep 2; adb devices"

echo "=== Step 2: 推送脚本 ==="
$PI_SSH "adb -s $HUD_IP:5555 push /dev/stdin /data/local/tmp/auto_brightness.sh" < auto_brightness_v4.sh
$PI_SSH "adb -s $HUD_IP:5555 shell chmod 755 /data/local/tmp/auto_brightness.sh"

echo "=== Step 3: 安装 Magisk 自启 ==="
$PI_SSH "cat /dev/stdin | adb -s $HUD_IP:5555 shell su -c 'cat > /data/adb/service.d/auto_brightness.sh && chmod 755 /data/adb/service.d/auto_brightness.sh'" < magisk_service_auto_brightness.sh

echo "=== Step 4: 启动 v4 ==="
$PI_SSH "adb -s $HUD_IP:5555 shell su -c 'killall auto_brightness.sh 2>/dev/null; nohup /data/local/tmp/auto_brightness.sh >/dev/null 2>&1 &'"

sleep 2

echo "=== Step 5: 验证 ==="
$PI_SSH "adb -s $HUD_IP:5555 shell ps -ef | grep auto_brightness | grep -v grep"
$PI_SSH "adb -s $HUD_IP:5555 shell logcat -d -s auto_brightness:* | tail -5"

echo "=== 完成 ==="
