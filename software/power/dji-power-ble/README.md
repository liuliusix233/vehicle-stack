# 户外电源（DJI Power 303 / dy303 / Cadillac 1000mini）蓝牙控制探索 — 归档

**归档日期**: 2026-08-23
**目标**: 脱离 DJI Home App，可编程控制 220V AC 开关 → 最终实现 XT5 "上车自动开 AC"
**状态**: ⏸ 暂停于「ESP32 蜜罐已就绪、未完成真机 C301 数据提取与 14 字节帧捕获」

---

## 目录

```
docs/          协议文档 + 方案总结
esp32/         ESP32 蜜罐固件源码（双模式：探针/蜜罐）
esp32/firmware/ 已编译二进制（可直接 esptool 烧录）
captures/      真机截图、nRF UI XML、8T 配置截图
scripts-8t/    8T nRF Connect GATT Server 配置脚本（adb 驱动）
duml/          DUML 协议工具（o-gs/dji-firmware-tools 系）
tasklogs/      探索过程的 .tasks 日志（nrf_* / 8t_* / pi_bt_*）
```

## 核心事实链（全部实锤）

| 项 | 值 |
|---|---|
| 设备 | POWER1000MINI-1009NX / MAC `<MAC>` / **免配对直连** |
| 固件 | 01.00.0300 / SN AJAXP1511009NX / device_project dy303 |
| App | DJI Home 1.5.15/1.5.16（Flutter + flutter_blue_plus，MTU 517） |
| GATT A002 | C300 READ=0x30 / C301 READ=512B 校准 / C302 WRITE / C303 WRITE_NR / **C304 WRITE（控制+心跳 14B 帧）** / C305 NOTIFY(+2902) / C306 INDICATE(+2902) / C307 READ；C300-C304 带 0x2901 描述符 |
| GATT A003 | C400 READ=0x30 / C401 READ |
| 控制 | DJI Home 每 10s 写 C304（14 字节）+ AC 操作时写；logcat `onCharacteristicWrite: c304 status: 0` |
| 遥测 | C305 NOTIFY 每秒 2 次，**需 C304 握手后才推** |
| 协议 | "类 DUML" 私有变体（有 cmdSet/cmdId 概念，CRC8/CRC16 多项式非标准，15+ 标准变体盲试全灭） |

## 已尝试并失败（详见 docs/DJI_BLE_PROBE.md）

云端写入、HTTPS 接口、协议嗅探等非 BLE 路径全部失败（方法不展开）；DUML 标准帧也盲试不通，最终走自定义帧。

## ESP32 蜜罐进度（2026-08-23 18:20-19:03）

- ✅ 主力机（<USER>@<LAN_IP>）USB 插 ESP32-D0WD-V3（COM6，CP210x，原 MAC <MAC>）
- ✅ 工具链: arduino-cli v1.5.1（C:\Users\<USER>\arduino-cli）+ esp32:esp32@2.0.17 + esptool v5.3.1（pip）
- ✅ 固件 `dji_esp32_honeypot.ino` 双模式编译通过（~1.1MB / 85% flash）：
  - `MODE_PROBE=1`（默认）: Central 扫描→连真机→读 C300/C301/C307/C400/C401 + 订阅 C305；目标不在射程 10s 重试
  - `MODE_PROBE=0`（-D 覆盖）: Peripheral 伪装（MAC <MAC> + 名字 POWER1000MINI-1009NX + A002/A003 全特征 + 0x2901/0x2902），C304 写入 → 串口 hex 打印
- ✅ 探针实测: 扫描正常（主力机位置可见 ~30 个 BLE 设备）；**真机不在射程**（或连接中不广播）
- ⚠️ C301 512B 真值**未拿到**（只有截图 c301_on_final.png/r301.png，VLM 转录不可信）；c301_data.h 目前全 0 占位
- ⚠️ 14 字节 AC 帧**未捕获**（蜜罐未做真机测试）

## 恢复操作指南（下次从这里继续）

### 烧录蜜罐（在主力机）
```cmd
:: 杀占用 + 恢复端口（CP210x 会莫名掉线）
schtasks /end /tn esp_cap 2>nul
powershell -Command "Get-Process powershell | Where-Object {$_.Id -ne 3084} | Stop-Process -Force"
pnputil /restart-device "USB\VID_10C4&PID_EA60\0001"

:: 编译（蜜罐模式）+ 上传
C:\Users\<USER>\arduino-cli\arduino-cli.exe compile --fqbn esp32:esp32:esp32 --build-path C:\Users\<USER>\dji_build --build-property "compiler.cpp.extra_flags=-DMODE_PROBE=0" C:\Users\<USER>\dji_esp32_honeypot
:: ⚠️ arduino-cli 内置 esptool 偶发 "port doesn't exist"（pyserial 却能开）→ 用独立 esptool 直烧:
python -m esptool --port COM6 write_flash 0x1000 dji_build\dji_esp32_honeypot.ino.bootloader.bin 0x8000 dji_build\dji_esp32_honeypot.ino.partitions.bin 0xe000 <arduino15>\packages\esp32\hardware\esp32\2.0.17\tools\partitions\boot_app0.bin 0x10000 dji_build\dji_esp32_honeypot.ino.bin
```
（或直接用归档 `esp32/firmware/*.bin`）

### 串口捕获（常驻）
```cmd
schtasks /create /tn esp_cap /tr "powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File C:\Users\<USER>\dji_esp32_honeypot\esp_capture.ps1" /sc once /st 23:59 /f
schtasks /run /tn esp_cap
:: 看日志: C:\Users\<USER>\esp_capture.log
```

### 真机测试（需用户配合）
1. **关掉真电源**（否则同 MAC 广播冲突，Android 随机连真/假）
2. S26U 打开 DJI Home → 应自动/手动连上 ESP32（名字 MAC 全同）
3. 点 AC 开/关 → 看 esp_capture.log 里的 `[C304 WRITE <<<]` 14 字节 hex
4. 拿到 2 帧不同 seq 的帧 → 穷举 CRC8/CRC16 多项式 → 协议破解

### 补充 C301 真值（可选，蜜罐连接被拒时再做）
- 方案 A: 断 S26U 的 nRF 连接 → ESP32 探针（若在射程）直读真机，串口出全量 hex
- 方案 B: S26U nRF 读 C301 → 值对话框 → 截图/长按复制 → `adb shell dumpsys clipboard`
- 拿到后替换 `esp32/c301_data.h` 重新编译

## 踩坑记录（重要）

1. **CP210x 掉线**：COM6 间歇性 "port doesn't exist" → `pnputil /restart-device "USB\VID_10C4&PID_EA60\0001"`（\<USER> 有管理员权限）
2. **arduino-cli 内置 esptool 4.5.1 偶发打不开 COM6**（pyserial 正常）→ 用 pip 版 esptool v5.3.1 直烧 bin
3. **nRF 特征行是自定义 View**，uiautomator dump 抓不到文本 → 用截图 + 坐标点击
4. **外设连接中不广播**：真机被 S26U 连着时 ESP32 扫不到 → 探针/蜜罐测试前先断连接
5. **Windows SSH 起的 powershell 杀不死**（session 0 存活）→ 用 taskkill /F /PID 精确杀
6. **esp_capture.ps1 与上传抢 COM6** → 上传前先 schtasks /end + taskkill
7. 主力机 3084 是用户空闲 PowerShell 窗口，别杀

## 关联资产（未复制进归档，原地引用）

- APK: `power_monitor/dji-home.apk`（295MB）+ 解包 `tmp/dji_apk/`
- 云监控: `power_monitor/dji_cloud_sync.py`（systemd dji-cloud-monitor 常驻，与 BLE 探索无关但共享设备）
- BLE 蜜罐 8T: <LAN_IP>:39777（nRF GATT Server 已配置，卡广播无名字）；S26U: <LAN_IP>:41019（adb）
- 蓝牙假扮逆向完整记录: `memory/2026-08-23.md` + `memory/2026-08-24.md`（如有）
