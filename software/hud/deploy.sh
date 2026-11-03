#!/bin/bash
# 在 Pi 上执行：从 SER 拉取最新 HUD 文件并重启服务（仅更新时用，运行时不需要网络）
S=http://<LAN_IP>:8477
set -u
sudo -n systemctl stop diyhud-kiosk 2>/dev/null
mkdir -p ~/diyhud/fonts ~/diyhud/pi ~/diyhud/systemd
for f in index.html rotate.html tile_server.py pi/kiosk-run.sh pi/warm_tiles.py pi/bridge.py fonts/hud-cjk.woff2 fonts/hud-num.woff2 systemd/diyhud-kiosk.service; do
  curl -fsS -m 20 -o ~/diyhud/$f $S/$f || echo "FAIL $f"
done
chmod +x ~/diyhud/pi/kiosk-run.sh
sudo -n cp ~/diyhud/systemd/diyhud-kiosk.service /etc/systemd/system/
sudo -n systemctl daemon-reload
sudo -n systemctl restart diyhud-server
sleep 2
sudo -n systemctl start diyhud-kiosk
sleep 4
echo "MD5=$(md5sum ~/diyhud/index.html | cut -d' ' -f1)"
echo "SERVER=$(systemctl is-active diyhud-server) KIOSK=$(systemctl is-active diyhud-kiosk)"
