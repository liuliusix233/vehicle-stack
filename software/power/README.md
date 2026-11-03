# Power Monitor（户外电源，BLE 本地）

> 只看本地蓝牙，**不碰云端**：不走大疆云接口、不涉及 token 提取。
> BLE 协议实现来自开源项目 [`zuyan9/ha-dji-power-ble`](https://github.com/zuyan9/ha-dji-power-ble)（0BSD），
> 本仓库做了裁剪与适配，见根目录 `ATTRIBUTION.md`。

## 安装

```bash
cd software/power
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
sudo usermod -aG bluetooth $USER      # 需要蓝牙权限，重新登录生效
```

## 采集链路

```
DJI 电源 ──BLE──► dji_power_ble（协议栈）──► pi-auto-ac/bridge.py ──► HA 实体
```

采集在 Pi 上跑，实体推到 HA（`sensor.power_*`），HUD 再从 HA 读。

## 两块电源的电量从哪来

| 电源 | 容量 | HA 实体 | 来源 |
| --- | --- | --- | --- |
| 2000E | 2048 Wh | `sensor.cadillac_battery` | HA 里的 BLE 集成（上游项目） |
| 1000 mini | 1008 Wh | `sensor.mini_power_battery` | 同上，`entity_prefix` 决定前缀 |

`pi-auto-ac/bridge.py` 只**读**这两个实体，用于自动 AC 决策与总电量显示，不生产它们。
实体名必须对上：集成换了 `entity_prefix`，`policy.json` 的 `soc_sensor` 也要跟着改，否则 HUD 显示 `unknown`。

## 脚本

| 文件 | 作用 |
| --- | --- |
| `pi-auto-ac/bridge.py` | 主服务：BLE 连接 + 自动 AC + 推 HA |
| `pi-auto-ac/dji_power_ble/` | BLE 协议栈（上游 0BSD 项目 vendored） |
| `car_alert_watch.py` | 低压 / 高温告警 |
| `ha_arm_dashboard.py` | HA 卡片数据整理 |

## 坑

- BLE 需要 `bluetooth` 组权限，改完要重新登录。
- 手机 App 连着电源时 BLE 单连接会被占用，Pi 抢不到。
- 电源会休眠，静止一段时间后 `no_data` 属正常，策略此时不动。