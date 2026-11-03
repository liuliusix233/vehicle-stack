# 凯迪拉克 XT5 OBD 数据采集架构

## 工程状态：搭建完成 ✅ (2026-07-07)

---

## 一、硬件栈

### 边缘计算节点：Raspberry Pi Zero 2 W
| 项目 | 值 |
|:----|:----|
| **型号** | Raspberry Pi Zero 2 W Rev 1.0 |
| **SoC** | BCM2710A1 (4× Cortex-A53, 1GHz) |
| **RAM** | 416MB (可用 ~250MB) |
| **存储** | 59GB microSD (占用 3.1GB，空闲 53GB) |
| **OS** | Debian 13 Trixie (aarch64), kernel 6.12.47+rpt-rpi-v8 |
| **WiFi** | 2.4GHz 802.11n，连接 ASUS2 |
| **蓝牙** | HCI 4.2, Broadcom BCM43436, UP RUNNING |
| **BT MAC** | <MAC> |

### OBD 扫描器：Vgate iCar Pro BT3.0
- **协议**: ISO 15765-4 CAN (11/29bit, 250/500 Kbaud)
- **连接方式**: Bluetooth 3.0 SPP → `/dev/rfcomm0`
- **支持读取**: 发动机转速/车速/水温/进气/O2传感器/故障码
- **限制**: 48V 轻混系统属于通用私有 CAN 矩阵，需 UDS 指令

## 二、网络拓扑

```
┌─────────────────────┐     Bluetooth SPP      ┌──────────────────────┐
│ 凯迪拉克 XT5        │◄──────────────────────►│ Pi Zero 2 W          │
│ CAN 总线 → Vgate    │      /dev/rfcomm0       │ <LAN_IP> (LAN) │
│ iCar Pro BT3.0      │                        │ <TS_IP> (TS)    │
└─────────────────────┘                        └─────────┬────────────┘
                                                          │ Tailscale
                                                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Home Assistant (<TS_IP>)                                       │
│  - Mosquitto MQTT Broker :1883                                      │
│  - REST API :8123                                                    │
│  - Location: 无锡锡山                                                 │
└─────────────────────────────────────────────────────────────────────┘
```

## 三、软件栈 (Pi Zero 2 W 端)

### 已安装 ✅
| 软件 | 版本 | 来源 |
|:----|:----:|:----|
| Tailscale | 1.98.8 | 官方 (用户手动安装) |
| python-obd | 0.7.3 | pip (venv: ~/obd-env) |
| paho-mqtt | 2.1.0 | pip (venv: ~/obd-env) |
| pyserial | 3.5 | pip (venv: ~/obd-env) |
| bluez | 5.82 | apt |

### ✅ 已部署 (2026-07-07)
所有脚本位于 `~/obd/`，通过 Tailscale IP <TS_IP> 管理。

| 文件 | 路径 | 用途 |
|:----|:----|:----|
| `obd_bridge.py` | `~/obd/obd_bridge.py` | 核心桥接脚本 (python-obd → HA REST API) |
| `obd_startup.sh` | `~/obd/obd_startup.sh` | 启动辅助 (rfcomm bind + bridge) |
| `obd_config.json` | `~/obd/obd_config.json` | 配置文件 (Vgate MAC, HA token, 轮询间隔) |
| `bluetooth_setup.sh` | `~/obd/bluetooth_setup.sh` | 蓝牙配对/绑定工具 |
| `obd-bridge.service` | `/etc/systemd/system/` | systemd 服务 (重启策略 10s/5次) |
| `sudoers-obd` | `/etc/sudoers.d/obd-bridge` | pi 用户 rfcomm 免密 |
| `ha_config.json` | `~/ha_config.json` | HA 长期访问令牌 |
| 日志 | `~/obd_logs/obd_bridge.log` | 运行日志 |

### 架构说明
- **数据传输**: 直接走 HA REST API (`POST /api/states/{entity}`)，不依赖本地 MQTT broker
- **蓝牙配对**: 使用 `bluetoothctl` 配对 Vgate iCar Pro，`rfcomm bind` 创建 `/dev/rfcomm0`
- **PID 发现**: 首次连接自动扫描支持的 OBD PID 并缓存
- **断线重连**: 车辆熄火后自动检测断开，每 5 秒尝试重连
- **优雅关闭**: SIGTERM 处理，关闭前推连接状态到 HA

### HA 传感器实体 (41 个，全部已注册)

**发动机 (5)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_rpm` | 发动机转速 | rpm |
| `sensor.xt5_speed` | 车速 | km/h |
| `sensor.xt5_engine_load` | 发动机负荷 | % |
| `sensor.xt5_absolute_load` | 绝对负荷 | % |
| `sensor.xt5_run_time` | 发动机运行时间 | s |

**温度 (5)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_coolant_temp` | 水温 | °C |
| `sensor.xt5_oil_temp` | 机油温度 | °C |
| `sensor.xt5_intake_temp` | 进气温度 | °C |
| `sensor.xt5_catalyst_temp` | 催化器温度 | °C |
| `sensor.xt5_ambiant_temp` | 环境温度 | °C |

**进气/空燃 (3)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_maf` | 进气流量 | g/s |
| `sensor.xt5_barometric_pressure` | 大气压 | kPa |
| `sensor.xt5_equiv_ratio` | 空燃比当量 | ratio |

**燃油系统 (8)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_fuel_level` | 油量 | % |
| `sensor.xt5_fuel_pressure` | 燃油压力(低压) | kPa |
| `sensor.xt5_fuel_rail_pressure` | 燃油轨压(直喷) | kPa |
| `sensor.xt5_long_fuel_trim` | 长期燃油修正 | % |
| `sensor.xt5_short_fuel_trim` | 短期燃油修正 | % |
| `sensor.xt5_long_o2_trim` | 长期氧修正 | % |
| `sensor.xt5_short_o2_trim` | 短期氧修正 | % |
| `sensor.xt5_fuel_status` | 燃油系统状态 | text |

**节气门/油门 (6)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_throttle_pos` | 节气门位置 | % |
| `sensor.xt5_throttle_pos_b` | 节气门位置B | % |
| `sensor.xt5_throttle_actuator` | 节气门执行器 | % |
| `sensor.xt5_relative_throttle` | 相对节气门位置 | % |
| `sensor.xt5_accel_pos_d` | 油门踏板D | % |
| `sensor.xt5_accel_pos_e` | 油门踏板E | % |

**电气/混动 (2)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_battery_voltage` | 电瓶电压 | V |
| `sensor.xt5_hybrid_battery` | 轻混电池电量 | % |

**氧传感器 (2)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_o2_b1s2` | 后氧传感器 | V |
| `sensor.xt5_o2_wideband_current` | 宽氧传感器电流 | mA |

**EVAP (2)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_evap_purge` | 碳罐吹洗 | % |
| `sensor.xt5_evap_pressure` | 碳罐压力 | Pa |

**里程/历史 (4)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_distance_since_clear` | 清除DTC后里程 | km |
| `sensor.xt5_distance_with_mil` | 故障灯亮时里程 | km |
| `sensor.xt5_warmups_since_clear` | 清除DTC后热车次数 | count |
| `sensor.xt5_dtc_count` | 故障码数量 | — |

**信息/状态 (3)**
| Entity ID | 名称 | 单位 |
|:----------|:----|:----:|
| `sensor.xt5_fuel_type` | 燃油类型 | text |
| `binary_sensor.xt5_obd_connected` | OBD 连接状态 | on/off |
| `sensor.xt5_last_update` | 最后更新 | timestamp |

## 四、部署状态

| 阶段 | 状态 | 备注 |
|:----|:----:|:-----|
| 硬件确认 | ✅ | Pi Zero 2 W 已验证 |
| 系统就绪 | ✅ | Debian 13, 64GB SD |
| 蓝牙 | ✅ | hci0 UP，待配对 Vgate |
| Tailscale | ✅ | <TS_IP>, 直连 |
| Python 依赖 | ✅ | obd 0.7.3 + paho-mqtt + pyserial |
| OBD 脚本 | ✅ | obd_bridge.py + bluetooth_setup.sh |
| HA 集成 | ✅ | 14 个传感器已注册 (REST API) |
| Systemd 服务 | ✅ | obd-bridge.service 已安装 (disabled) |
| 车上配对 | ✅ | V-LINK <MAC> (rfcomm0) |
| 服务启动 | ✅ | obd-bridge.service (enabled + running) |
| 数据推送 | ✅ | 11 个 PID 实时推 HA |

## 五、已知限制
1. **标准 OBD-II 只能读基础数据**，48V 轻混扩展 PID 需逆向
2. **车载 WiFi** 依赖手机热点或车载 4G 模块
3. **Pi 内存有限** (416MB)，轮询脚本需控制内存使用
4. **挂车熄火会断电**，需处理优雅停止
