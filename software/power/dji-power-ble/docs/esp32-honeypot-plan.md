# ESP32 蜜罐方案总结（盒子 2026-08-23 提供）

> 针对「蜜罐连不上」最后几公里的解决方案文档，已按此实施。

## 广播无名字的底层真相
- 31 字节物理限制：主广播包最大 31B。8T 的 `020102030302A0` 占 7B，理论上能塞 20B 名字，但安卓 OreoAdvertiser 常截断。
- 需把设备名 POWER1000MINI-1009NX 放进广播/Scan Response；nRF Connect Server 模式难控制底层 → **换 ESP32 硬件解决**。

## ESP32 完美替身（已实施）
- `esp_ble_gap_config_adv_data` / `esp_base_mac_addr_set` 自定义任意广播字节 + **MAC 硬改 <MAC>**
- DJI Home 连上 ESP32 写 C304 → 串口立刻打印 14 字节类 DUML 帧 hex，**彻底绕过 AppGuard**

## 特征值(READ)与握手时序（已实施）
- C301 512B 静态校准 + C300/C400 `0x30` 预置为初始值（⚠️ C301 真值未拿到，c301_data.h 占位全 0）
- 防 App 因读取超时/格式错误主动断开

## 类 DUML 私有 CRC 破局（待实施）
- 大疆大概率为联名款换了标准 DUML 的 CRC8(非0x8C)/CRC16(非0x8408) 查表多项式
- 蜜罐骗出两次 Sequence 不同的有效控制帧 → 现成穷举脚本秒算私有多项式

## 实施状态
- ✅ ESP32 工具链 + 双模式固件（探针/蜜罐）编译通过
- ✅ 蜜罐固件二进制已归档（esp32/firmware/）
- ⏸ 14 字节帧捕获：等真电源关机 + S26U DJI Home 连 ESP32 测试
- ⏸ C301 真值：探针直读（需射程内）或 nRF 值对话框提取
