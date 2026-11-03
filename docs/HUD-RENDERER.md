# DIYHUD 轻量渲染 + Pi 稳定性修复（2026-10-31 夜）

## 背景
kiosk（cog + WPEWebKit）渲染地图页时：
- WPEWebProcess 43.5MB RSS + **165MB swap** + **89% CPU**
- 整机 swap 253MB / 416MB，load 3.3，SoC 77.4°C
- 表现：隧道每 ~20-34 秒重建、SSH 卡 banner exchange、TCP 能握手但 sshd 不吐 banner

## 结论（先定性）
| 怀疑项 | 结论 | 证据 |
|---|---|---|
| WiFi 弱 | 否 | wlan0 -59 dBm，quality 51/70 |
| 过热限流 | 次要 | 77.4°C，throttled=0x20000（历史降频，当前位=0） |
| 内存/CPU 打满 | **是** | swap 253MB、WPE 89% CPU |
| 隧道被 NAT 踢 | **是** | 无心跳，~20s 空闲断开，重连 10s 退避 |

## 轻量渲染器
- 代码：`diyhud/fb_dash.py`（Pi 上 `/home/<USER>/diyhud/fb_dash.py`）
- 服务：`/etc/systemd/system/diyhud-fb.service`（User=pi, Group=video, Restart=always, MemoryMax=120M）
- 环境变量：`FPS=2 DX=130 DY=0 ROT=90 FLIPY=1`（方向沿用原 kiosk：rotate 90 + 纵向翻转 + dx=70 偏移）
- 数据：HTTP `http://127.0.0.1:8477/state.json`（读文件会 Permission denied，state.json 是 root:600）
- 内容：车速/档位/转速条、导航（动作/距离/路名/剩余/ETA）、红绿灯+倒计时、温度/电压/里程/SOC、无数据横杠
- 实测：RSS 23-49MB（稳定后 ~23MB）、CPU ~10%、swap 不增；温度 77.4→69.8°C

### 踩坑
1. `Image.tobytes("raw","BGR;16")` 在 Pi 的 PIL 11.1.0 报 `No packer found from RGB to BGR;16` → 改 numpy 手 pack RGB565
2. `os.path.getsize("/dev/fb0")` = 0 → 尺寸读 `/sys/class/graphics/fb0/virtual_size` + `bits_per_pixel`
3. Py3 f-string 里中文路径/字体：CJK 用 `/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf`，DejaVu 无中文
4. scp 大文件（691200B framebuffer）会截断（链路抖）→ 在 Pi 端转 PNG 再 scp

### 回滚
```bash
sudo systemctl disable --now diyhud-fb
sudo systemctl start diyhud-kiosk      # 旧 WebKit 版
```

## 隧道心跳修复
- 文件：`/home/<USER>/pi_tunnel.py`，备份 `/home/<USER>/pi_tunnel.py.bak_hb`
- 改动：`ws_send` 加 `_send_lock`；run() 起 12 秒心跳线程发 WS ping(opcode 0x9)；收到 0xA 忽略
- 验证：`journalctl -u pi-tunnel -f | grep 'HB ping'`
- 效果：21:57:34 重启后 **26 分钟 0 次断连**（修复前 8 分钟 14 次）

## 关键命令
```bash
# 隧道健康（家里 relay）
curl -s http://127.0.0.1:19001/car-health      # pi_online / car_online
curl -s http://127.0.0.1:8898/ | head -c 300   # 经隧道读 Pi :8077

# Pi 资源
ssh pi@<TS_IP> 'free -m; uptime; vcgencmd measure_temp; vcgencmd get_throttled'
ssh pi@<TS_IP> 'ps -eo pid,rss,pcpu,args --sort=-rss | head'
ssh pi@<TS_IP> 'cat /proc/net/wireless'   # wlan0 信号 dBm

# 渲染器
sudo systemctl status diyhud-fb
sudo journalctl -u diyhud-fb -n 20 --no-pager
```

## 今日日志
- `logs/2026-10-31/car-relay-pi-events.log`（relay 全量连断事件）
- `logs/2026-10-31/pi_compare.log`、`pi_observe.log`

## 待办
- 微信通道 `1ba7b0213419-im-bot` 发送失败（contextToken missing / ret=-2 prepare failed），需 `openclaw channels login` 扫码
- 仪表方向待人工确认（我侧只能读 fb，无法判断物理面板朝向）
- 地图渲染未迁（v1 只做仪表）
