# CAR_POWER_CONTROL.md — 车载电源控制总览（DJI Power 2000 Elite + 1000mini）

> 一页式速查。数据来源：车机 Pi 的 BLE 直连（不走云端）。

## 1. 架构

```
车 12V(SDC) ──> DJI Power 2000 Elite (2度电, SN CWSXP9200A06GA)
                   │  AC 220V 输出 ──> 给 DJI Power 1000mini 充电 (1度电, SN AJAXP1511009NX)
                   └── BLE 直连(免云) ──> 车机 Pi Zero 2W (dji-ble-bridge.service, HTTP :8095)
                                            ├─ 自动 AC 策略(车电压>12.9V 开 / <12.68V 关)
                                            ├─ 轨压保护(rail_guard)
                                            └─ 推 HA 实体 sensor.pelite_* / sensor.vehicle_battery_total
24h 负载(摄像头/HUD/OBD 桥/…) ── 走 1000mini（自耗小）；1000mini 由 2000E 的 AC 补
```
- 2000E 的 BLE MAC：`<MAC>`（广播名 `PowerAuro2000Elite-0A06GA`）
- 1000mini 的 BLE MAC：`<MAC>`（`Power1000Mini-1009NX`）
- pair_key 取法：`GET {api_base}/app/api/v1/users/devices/list`（头 `x-member-token`）→ `pair_info.pair_key`（32 位）；脚本 `power_monitor/fetch_pair_key.py <SN> <out.json> [MAC] [NAME]`（只写文件、不回显）
- 协议：社区 `zuyan9/ha-dji-power-ble`；2000 Elite = 广播 model code `0x9E`、走 Power 2000 档（**明文** `0x20/0x80/0x00`）；握手 `0x6A/0x00`→4B challenge→`0x6A/0x01`+pair_key；键 `0x0D`=power_sw（行=type/seq/sw，**1=开 2=关**；AC=type2/seq1，type3=USB-A、4=USB-C、5/6=SDC、7=车充）；SET `0x63` 需带 `0x0E` 客户端规则 + 逐键 ack + 回读

## 2. 车机 Pi 上的组件

| 项 | 值 |
|---|---|
| 目录 | `~/dji_ble/`（venv + 协议包 `dji_power_ble/` + `pair.json` 0600 + `ha_config.json` 0600 + `bridge.py` + `policy.json`） |
| 服务 | `dji-ble-bridge.service`（root 级, User=pi, `Restart=always`, `ExecStartPre=bluetoothctl disconnect <MAC>`） |
| HTTP | `:8095` → `GET /state`（含 auto_ac / vehicle_total_pct）、`GET /health`、`GET /set?key=***&on=0\|1[&force=1]` |
| 日志 | `~/dji_ble/bridge.log`（策略动作 = `auto-ac: 12.9xV -> AC ON/OFF`；轨压 = `rail guard: ...`） |
| 依赖 | **必须钉版** `bleak==0.22.3` + `bleak-retry-connector==3.10.0`（pip 默认给 bleak 3.x，API 不兼容） |
| 一键 AC | `bash tools/dji_ac_ble.sh on\|off\|status`（会临时抢 BLE，先 `systemctl stop dji-ble-bridge`） |

## 3. HA 实体

- 开关（helper，`input_boolean.pelite_*`）：`auto_ac`(策略总闸) / `ac` / `usb_c1` `usb_c2` `usb_c3` / `usb_a1` `usb_a2` / `sdc1` `sdc2`
- 传感器（`sensor.pelite_*`）：`battery` `output_w` `input_w` `usb_c3_w` `temperature` `runtime_min` `switch_summary` `ble_result` `firmware` `car_voltage` `auto_ac` `car_voltage_alert`
- **`sensor.vehicle_battery_total`**「车辆电池总电量」= `(2048×2000E% + 1008×mini%) / 3056`（每 20s 推；属性 `battery_2000e` / `battery_1000mini` / `capacity_wh=3056`）
- 电量实体（BLE 集成写入）：`sensor.cadillac_*`（2000E）、`sensor.mini_power_*`（1000mini）

## 4. 车载 AC 策略（`~/dji_ble/policy.json`，**改完 ≤60s 热生效，不用重启**）

| 参数 | 值 | 含义 |
|---|---|---|
| `on_v` | **12.9** | 车电压 ≥ 此值持续 `hold_on_s` → 开 AC |
| `off_v` | 12.68 | ≤ 此值持续 `hold_off_s` → 关 AC |
| `hold_on_s` / `hold_off_s` | 12 / 15 | 需持续满足的时长 |
| `min_interval_s` | 30 | 两次策略动作硬下限（防抖） |
| `ignore_off_after_on_s` | 60 | 开 AC 后 60s 内不响应"关" |
| `ignore_on_after_off_s` | 30 | 关 AC 后 30s 内不响应"开" |
| `manual_hold_s` | 900 | 手动拨 AC → 限时接管 15 分钟，到点交还策略 |
| `rail_guard` | true | 轨压保护，见 §5 |
| `arm_switch` | `input_boolean.pelite_auto_ac` | 关掉 = 纯手动模式 |

**实测电压口径（调参依据）**：行车 12.9–13.9V（智能发电机调压，会回落到 12.7）/ 打车瞬间 11.99 / 停车 12.2–12.6 / **停放时车会自己把轨压补到 ~12.80（轻混维护脉冲）** ← 这就是 `on_v` 必须 >12.8 的原因。

## 5. 轨压保护（rail_guard）

- `< 11.8V` 持续 20s → HA 持久通知 + `sensor.pelite_car_voltage_alert=low`
- `< 11.5V` 持续 60s → **自动关 AC**（卸掉给 1000mini 的负载）
- 恢复（>12.0V）→ 告警复位为 ok
- 背景：熄火纯电瓶带 377W 就会砸到 11.0V（≈30A 铅酸内阻压降）

## 6. HUD（车萝卜 C2-Elite）联动

- 数据：`hud-apk/app/src/main/java/com/vox/hud/HudService.kt#fetchHaBattery` 每 5s 拉 **7 个 HA 实体**（公网 `https://nas.<PRIVATE_DOMAIN>:8443`）：`cadillac_battery/charge_power/discharge_power` + `mini_power_battery/discharge_power/charge_power` + `cadillac_sdc_voltage`
- 显示（`app/src/main/assets/dashboard.html`，主题 A）：**一条电量条 = 3056Wh 合计百分比**；**电压 = SDC 电压**；**OUT = 两台总输出**；IN 不变；无 NANO 标
- 构建：`cd hud-apk && bash build.sh`（**必须 direct exec**，`launch_t` 的 ulimit 会让 JVM OOM）
- 部署：`bash tools/ops/hud_deploy.sh <HUD_IP>`（scp→Pi→adb push→pm install -r→force-stop+start→截图）；**HUD IP 常漂，推前先扫网段**
- 主题 B（纯黑运动皮肤）未同步 §6 的改动

## 7. 常用命令

```bash
# 车机桥
bash tools/ops/pi_dji_bridge_deploy.sh              # 推 bridge.py+policy.json+ha_config 并重启
bash tools/ops/pi.sh 'curl -s http://127.0.0.1:8095/state'   # 看实时状态(含 auto_ac/vehicle_total)
bash tools/ops/pi.sh 'grep -aE "auto-ac|rail guard" ~/dji_ble/bridge.log | tail -20'
bash tools/ops/pi_dji_ctl.sh 'sweep|state|ac off|port 3 1 on' # 一次性控制(会抢 BLE)
# HA 侧
bash tools/ops/ha_pelite_helpers.py                 # 建/查 pelite_* helper(幂等)
bash tools/ops/ha_pelite_dashboard.py               # 建/刷 /power-elite 看板
python3 tools/ops/ha_ws_cmd.py '<json>'             # 任意 HA WS 命令
# 交叉验证：直接看 HA 里 `sensor.power_*` 的 AC 状态与本地 BLE 读数是否一致
```

## 8. 坑（都踩过）

1. `systemctl enable --now X` 对**已在运行**的 unit **不会重启** → 改代码必须显式 `restart`。
2. 桥**被 kill 后 BlueZ 可能死握链路** → 站点停广播 → 桥永远扫不到（`bluetoothctl info` 显示 `Connected: yes` 但桥报 not found）→ 靠 `ExecStartPre` + 连续失败 4 次自清。
3. **一个 BLE 适配器只能有一个扫描器**：独立进程再开扫描 → 谁都收不到（btn-watch 就这么废的）；要监听就并进 bridge 同进程。
4. `/api/template` 返回**纯文本**，客户端别 `json.loads`。
5. 快速连打多次写 → 站点偶发 `station omitted valid power_switches configuration`（**写入其实生效**，只是回读失败）→ 单发 + 落定 1s + 重试。
6. `pkill -f "<pattern>"` 写在 ssh 命令里会**匹配到 ssh 自身命令行**把自己杀掉 → 用 `instal[l]` 或 PID 文件。
7. sshpass 密码直接写命令行会被本会话的密钥过滤改成 `***` → 用 `tools/ops/pi.sh`（运行时 grep 密码）。

## 9. 待办 / 未决

- [ ] 双线 800W 或 24V 升压 600W：盒子在考虑加线 → 接好后实测 `sdc_charge_w` + 轨压曲线（判据：行车 ≥13.2V 算过关）
- [ ] `on_v=12.9` 是否偏紧（行车低值 12.90）→ 若"该开没开"换「12.85 + 30s 上升趋势」判据
- [ ] 主题 B 皮肤同步 HUD 改动
- [ ] 未映射的可控项：充电限值 `0x05`、备电 `0x06`（当前 not offered）、eco/充放电功率 `0x18`
- [ ] 想要**原生 switch 实体**（去掉 3s 轮询 + helper 回写）需一次 HA 重启 + YAML/自定义集成