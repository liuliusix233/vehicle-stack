# pi-auto-ac（Pi 侧自动 AC）

Pi 上跑的服务：车一启动就开户外电源的 AC 输出，熄火就关，顺带看住电瓶电压和电量。

源码来自实车（Pi `dji_ble/`），`bridge.py` 是主体。

## 它解决什么问题

车上常电负载是实的：哨兵 DVR + Pi Zero 2 W 一直吃 DC 口约 9W。AC 逆变器长期开着没有意义，白白多一层损耗；但手动开关又容易忘。
这套逻辑用**电瓶电压**判断发动机是否在转：

- 发动机在转 → 发电机输出 → 母线升到 13.2V+ → 开 AC
- 熄火 → 电压回落到 13.0V 以下 → 关 AC

电压比「点火信号」更可靠：不用改线、不依赖 VHAL、熄火后还有几十秒余量。

## 文件

| 文件 | 作用 |
| --- | --- |
| `bridge.py` | 主服务：BLE 连接 + AC 策略 + HA 同步 |
| `dji_power_ble/` | DUML 协议实现（BLE 直连电源） |
| `dji_ctl.py` | 手动开关 AC 的命令行工具 |
| `ac_probe.py` | 探测电源支持的字段 |
| `btn_watch.py` / `btn_scan.py` | 物理按键监听 |
| `systemd/*.service` | 开机自启 |
| `policy.example.json` | 策略参数（复制成 `policy.json` 再改） |

## 部署

```bash
scp -r pi-auto-ac pi@<PI_HOST>:~/dji_ble
ssh pi@<PI_HOST> 'cd ~/dji_ble && cp policy.example.json policy.json'
ssh pi@<PI_HOST> 'sudo cp systemd/*.service /etc/systemd/system/ && sudo systemctl daemon-reload'
ssh pi@<PI_HOST> 'sudo systemctl enable --now dji-ble-bridge'
```

## 验收

```bash
systemctl status dji-ble-bridge --no-pager      # active (running)
journalctl -u dji-ble-bridge -n 20 --no-pager   # 看到 auto-ac: 12.9V -> AC ON
```

手动验证：怠速 15 秒后 AC 应自动打开；熄火 15 秒后自动关闭。

## 注意

- `policy.json` 不在仓库里（含实体名与阈值，按自己的改）。
- `ha_config.json` / `pair.json`（HA token、BLE 配对密钥）**绝不入库**。
- 策略细节见 `docs/AUTO-AC.md`。