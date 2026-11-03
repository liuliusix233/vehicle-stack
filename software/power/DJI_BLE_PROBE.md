# DJI Power 303 BLE 控制逆向 — 完整状态总结（2026-08-23 18:20）

## 目标
脱离 DJI Home App，可编程控制 Cadillac 1000mini（DJI Power 303 / dy303）的 220V AC 开关，最终实现"上车自动开 AC"。

## 设备与链路事实（全部实锤）
- 设备：POWER1000MINI-1009NX / MAC <MAC> / **免配对直连**（无 bonding）
- 固件 01.00.0300 / SN AJAXP1511009NX / device_project dy303
- DJI Home 1.5.15/1.5.16（Flutter + flutter_blue_plus 插件，MTU 517）

### GATT 结构（nRF Connect 枚举）
- **A002 服务**（控制+遥测）：
  - C300 READ = 0x30
  - C301 READ = 512B 静态校准数据（AC on/off 不变）
  - C302 WRITE（DJI Home 未使用）
  - C303 WRITE NO RESPONSE
  - **C304 WRITE（DJI Home 控制+握手通道，14 字节帧）**
  - C305 NOTIFY（遥测，每秒 2 次，**需 C304 握手后才推**）
  - C306 INDICATE（响应通道）
  - C307 READ（读不出）
- **A003 服务**：C400 READ = 0x30、C401 READ

### 控制通道确认（logcat 实锤）
- DJI Home 连接后**每 10 秒写 C304（14 字节）**心跳 + AC 操作时额外写
- `[FBP] onCharacteristicWrite: c304 status: 0` / `onWriteCharacteristic length=14` / GATT_SUCCESS
- **App 蓝牙控制 AC 有效**（盒子实测 on/off）
- analytics.db 记录 app_click（switch_type:home_ac）+ 每秒遥测（dy_* 字段）

## 协议猜测：DUML（盒子朋友提供）
- 标准 DJI DUML：0x55 magic + ver_length + crc8(0x8C/0x77) + sender/receiver(低5位type) + seq + cmd_type + cmd_set + cmd_id + payload + crc16(0x8408/0x3692)
- 头 11 字节 + 1 字节 payload + 2 字节 CRC16 = **14 字节吻合**
- dji-firmware-tools (o-gs/comm_mkdupc.py) 验证 CRC 算法正确
- **❌ 试了 15+ 变体（sender/recv 4 组 + ACK 0-3 × seq 0-2）全部被设备静默忽略**
- **🔑 关键否定：libapp.so 里没有标准 DUML CRC 表（0x1189/0x5E 系列）→ Power 用的是"类 DUML"私有变体**（有 cmdSet/cmdId 概念但帧布局/CRC 不同）

## 蜜罐方案（BLE 欺骗，盒子提出，最接近成功）
**原理**：免配对 = 无认证，用 8T 伪装成大疆电源（GATT Server），让 DJI Home 自己把 14 字节写过来。

### 8T 配置完成度
- ✅ nRF Connect 4.29.1 安装（no.nordicsemi.android.mcp）
- ✅ GATT Server：**Sample configuration 含 A002 服务 + C300-C307 全特征**（C300/301 READ、C302 WRITE、C303 WRITE、C304 WRITE+WRITE NO RESPONSE、C305 NOTIFY+2902、C306 NOTIFY+2902、C307 READ）+ **A003 服务 + C400/C401 READ**
- ✅ GATT Server 已启用（logcat: addService 0000a002 status=0）
- ✅ 广播启动（logcat: onAdvertisingSetStarted status=0）
- ❌ **广播数据只有 `020102030302A0`（Flags + Service UUID 0xA002），无设备名、Scan Response 空**
- ❌ S26U nRF **扫不到**（无 POWER 名字设备）；DJI Home 显示"尝试连接 Cadillac1000mini"但连不上
- ✅ 8T 蓝牙硬件正常（自身能扫到 7 个设备）

### 卡点
1. **广播无名字**：Server 模式广播（OreoAdvertiser）不带名字；ADVERTISER 独立广播（带名字 POWER1000MINI-1009NX）启动后未见生效（logcat 仍是无名字数据）——疑似 Server 广播占用 advertising slot 或 ADVERTISER 启动未成功
2. **DJI Home 连接失败**：即使被找到（A002 服务），App 可能校验：设备名、READ 特征值（C300/C301 返回空）、握手时序

## 其他尝试（结论）

我们还试过几条非 BLE 的路径（云端写入、HTTPS 接口、协议嗅探），全部失败。
具体方法不在公开仓库展开——没有可复用的成果，公开只会误导。
| logcat 抓写入 value | 只打印 length=14，无内容 |
| Frida 注入 | 无 root；AppGuard 反调试 |
| blutter 逆向 libapp.so | NDK+dartvm 3.4.4 编译成功，但 **AppGuard 加密了 Dart snapshot**（"Cannot find Dart VM Snapshot Data"） |
| DUML 标准帧盲试 | 15+ 变体全灭（私有变体） |

## 现成资产
- **8T**（<LAN_IP>:39777）：蜜罐环境完整，只差广播名字/连接验证
- **S26U**（<LAN_IP>:41019）：DJI Home 客户端 + nRF Connect + ADBKeyboard
- DUML builder（tmp/duml/，CRC 验证通过）
- 协议文档：power_monitor/DJI_BLE_PROBE.md
- 8T 系统蓝牙名已改 POWER1000MINI-1009NX（但 Server 广播不用系统名）
- 真设备 C301 512B 校准数据已捕获（可用于给假设备 READ 特征设初始值）

## 求助要点（拿去问人）
> 有台 DJI Power 303（dy303），DJI Home App 通过 BLE 写特征 C304（14 字节帧）控制 AC 开关，每 10 秒心跳。帧有 magic+CRC 校验，但**不是标准 DUML**（CRC 多项式不同）。遥测走 C305 NOTIFY（需 C304 握手后推）。用 nRF Connect 伪装同款 GATT 服务（A002+C300-C307）让 App 连接，**App 能发现但连不上**，且 **Server 模式广播不带设备名**（只有 Service UUID），ADVERTISER 独立广播（带名字）启动后实际数据仍无名字。
> 问题：1) DJI Power 的 BLE 私有帧格式/CRC 是什么？2) DJI Home 连接时校验什么（名字？特征值？握手）？3) 怎么让 App 连上伪装设备（或抓到 14 字节写入）？

## 下一步候选
1. 修广播名字：关 Server → 纯 ADVERTISER 广播（带名字）→ 验证 S26U 能扫到名字 → 再开 Server 测连接
2. 给 READ 特征设初始值（C300=0x30、C301=512B 校准数据、C400=0x30）
3. A003 服务已在 → 确认 App 需要
4. 若 8T 广播实在发不出名字：换 Note20（root，可 Frida）或 SER VM 蓝叠
5. 逆向"类 DUML"：从 libapp.so 的 cmdSet 字符串 + 协议类（OsdProtocol/StateProtocol）继续挖（blutter 死路，需其他 Dart snapshot 工具）
