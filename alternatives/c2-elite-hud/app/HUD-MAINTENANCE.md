# HUD 系统维护手册 — 2026-07-12

> 车萝卜 C2-Elite + Pi Zero 2W + HudDashboard APK + HA 混合架构

## 目录

1. [最终架构](#1-最终架构)
2. [文件清单与位置](#2-文件清单与位置)
3. [数据流详解](#3-数据流详解)
4. [版本历史](#4-版本历史)
5. [常见故障与诊断](#5-常见故障与诊断)
6. [管理命令速查](#6-管理命令速查)
7. [APK 构建与部署](#7-apk-构建与部署)
8. [已知待办](#8-已知待办)

---

## 1. 最终架构

```
OBD ──→ HUD 内置OBD线 ──→ HUD AIOS (AIOBD## logcat)
                           │
                     ┌─────┴──────┐
                     │             │
              本地 logcat      ADB WiFi
              (su logcat)   (Pi 轮询)
                     │             │
                     │    Pi Zero 2W (<LAN_IP>)
                     │    ├─ hud_obd_proxy.py (:8080)
                     │    │   └─ OBD解析 + HA数据注入
                     │    ├─ server.py (:8888)
                     │    │   └─ HUD遥控器 (app launch)
                     │    └─ Tailscale → HA (<TS_IP>)
                     │             │
                ┌────┘             │
                │                  │
           HudDashboard APK        │
           (com.vox.hud)           │
           ├─ HudService (前台服务) │
           │  ├─ LocalLogcatReader │  ← 实时OBD (~200ms)
           │  └─ PiDataProvider    │  ← HA补充 (3s轮询)
           ├─ TYPE_ORIENTATION     │  ← 方向传感器
           └─ WebView(dashboard)   │
```

**关键原则：**
- 时速/rpm等OBD数据：**本地 logcat**（延迟 ~200ms，零网络）
- 户外电源/燃油回退：**Pi 代理**（3s 轮询）
- 指南针：**HUD 方向传感器**（TYPE_ORIENTATION）

---

## 2. 文件清单与位置

### 2.1 APK 源码（本机 `/home/<USER>/.openclaw/workspace/jarvis/hud-apk/`）

| 文件 | 行数 | 功能 |
|:----|:----:|:----|
| `app/src/main/java/com/vox/hud/HudService.kt` | 226 | **主力**：前台服务，混合数据源（本地logcat + Pi HA） + 方向传感器 |
| `app/src/main/java/com/vox/hud/MainActivity.kt` | 151 | **主力**：WebView 全屏，接收广播注入JS |
| `app/src/main/java/com/vox/hud/ObdData.kt` | 159 | **主力**：数据模型 `ObdSnapshot`，toJson/fromJson/fromLogcatLines |
| `app/src/main/java/com/vox/hud/ObdDataProvider.kt` | 57 | **废弃**（不参与编译）：旧Pi HTTP轮询 |
| `app/src/main/java/com/vox/hud/PiDataProvider.kt` | 35 | **低频使用**：Pi HA补充数据抓取 |
| `app/src/main/java/com/vox/hud/LocalLogcatReader.kt` | 107 | **运行中**：`su -c logcat` 流式读AIOBD## |
| `app/src/main/java/com/vox/hud/HudBootReceiver.kt` | 53 | **运行中**：开机自启 MainActivity |
| `app/src/main/AndroidManifest.xml` | - | HOME+DEFAULT+LAUNCHER（系统默认桌面）|
| `app/src/main/assets/dashboard.html` | ~200 | HUD 显示前端（含指南针）|
| `dist/HudDashboard.apk` | - | **当前运行 APK** (1.5M) |
| `tools/` | - | 编译工具链（kotlinc, android-sdk, build-tools）|
| `build.sh` | - | 构建脚本 |
| `archive-before-hybrid/` | - | 混合模式前的所有源码存档 |

### 2.2 Pi Zero 2W（<LAN_IP> / Tailscale <TS_IP>）

| 文件 | 功能 |
|:----|:------|
| `~/hud_obd_proxy.py` | **OBD代理 :8080**：ADB轮询logcat + HA数据注入 + ODO基线持久化 |
| `~/hud_remote/server.py` | **遥控器 :8888**：HUD app launch + 截图 + 按键模拟 |
| `~/hud_remote/start.sh` | 8888启动脚本（自动扫HUD IP + sed替换 + 启动） |
| `~/.hud_odo_state.json` | ODO 基线状态持久化 |
| `/tmp/hud_proxy.log` | 8080代理日志 |
| `~/obd_logs/obd_bridge.log` | 旧OBD桥日志（已退役） |
| `~/hud_archive_before_hybrid/` | Pi 代理修改前备份 |

### 2.3 HUD 本身（<LAN_IP>XX:5555）

| 项目 | 内容 |
|:----|:-----|
| Model | 车萝卜 C2-Elite (mt5873_cd) |
| Android | 8.1.0 (API 27) |
| Root | Magisk ✅ (服务端 `su` 可用) |
| ADB | Authless (service.adb.secure=0) |
| 默认桌面 | `com.vox.hud/.MainActivity` |
| 预装桌面 | `com.ileja.carrobot/.SplashActivity` (车萝卜原生) |
| Tailscale | 已装但未登录 |
| 网络 | 车WiFi <LAN_IP>/24, DHCP漂移 100-125 |

---

## 3. 数据流详解

### 3.1 混合模式数据合并逻辑

```
LocalLogcatReader (实时OBD, ~200ms)
  ├── speedKmh, rpm, coolantTemp, batteryVoltage
  ├── fuelLevel (原始OBD), engineLoad, throttlePos
  ├── intakeTemp, oilTemp, ambientTemp, fuelConsumption
  └── odometer (米单位，原始值)
         ↓
PiDataProvider (HA补充, 3s轮询)
  ├── power (户外电源电量)
  ├── fuelLevel / fuelLevelHA / fuelLevelOS (HA回退)
  ├── odometer (公里，基线已修正)
  ├── speed/rpm/voltage等 (fallback / 非实时)
         ↓
broadcastMerged() [HudService.kt]
  ├── OBD字段 → 优先本地logcat（最新）
  ├── odometer → 优先Pi代理（基线修正）
  │   └── fallback: ODO_BASE_KM (30607) 当无Pi数据时
  ├── power → 仅Pi代理
  ├── heading → 方向传感器覆盖
  └── piOn/wifiOn → 硬编码 true（本地logcat通即在线）
         ↓
MainActivity → WebView.evaluateJavascript("updateData({...})")
```

### 3.2 ODO 里程计算（Pi 代理侧）

```python
ODO_BASE = 30607           # 真实车里程 (km)
first_odo = 2647           # 部署时HUD内部里程 (m)
current_raw = 4176          # 当前HUD内部里程 (m)

# 公式
odo_km = ODO_BASE + int((current_raw - first_odo) / 1000)
#      = 30607   + int((4176 - 2647) / 1000)
#      = 30607   + 0
#      = 30607
```

- `first_odo` 持久化在 `~/.hud_odo_state.json`
- 里程跳跃 >2000m 自动重置基线（检测AIOS重启）
- APK 侧 `ODO_BASE_KM = 30607` 作为无Pi数据时的 fallback

### 3.3 OBD 字段映射（AIOBD## 中文 -> 英文字段）

| AIOBD 中文键 | ObdSnapshot | 单位 |
|:------------|:-----------|:----|
| 车辆速度 | speedKmh | km/h |
| 发动机转速 / 转速 | rpm | rpm |
| 水箱温度 | coolantTemp | °C |
| 电瓶电压 | batteryVoltage | V |
| 剩余油量 | fuelLevel | % |
| 发动机负荷 | engineLoad | % |
| 绝对节气门位置传感器 | throttlePos | % |
| 进气口温度 | intakeTemp | °C |
| 车辆环境温度 | ambientTemp | °C |
| 行驶里程 | odometer | **米** |
| 瞬时油耗 | fuelConsumption | L/100km |

---

## 4. 版本历史

### v6 — 混合模式（当前，22:53）
- **架构：** LocalLogcatReader（实时OBD）+ PiDataProvider（3s HA）+ 方向传感器
- **改善：** 时速延迟 ~1.1s → ~200ms；PI图标不再闪烁
- **ODO fix：** 合并逻辑改为优先 Pi 代理（基线修正公里值），本地米值只做fallback
- 存档：`archive-before-hybrid/`
- APK：`dist/HudDashboard.apk` (1.5M)

### v5 — 双通道 + 方向传感器（22:10-22:30）
- HudService 加 `lastGoodSnapshot` 缓存（断连保持10s）
- `TYPE_ORIENTATION` 传感器监听 → 指南针
- `HEADING_OFFSET=315°` 补偿HUD安装角度
- ODO 基线持久化 `~/.hud_odo_state.json`
- AIOS 重启检测（里程跳跃>2000m自动重置）

### v4 — 第二版 APK（HOME 注册 + 绿框修复，20:27-20:47）
- Manifest 加 `HOME+DEFAULT` category → 系统默认桌面
- `setDefaultFocusHighlightEnabled(false)` → 无绿框
- CSS `*:focus{outline:none}` → 无焦点边框
- 8888 遥控器 `start.sh` IP 自动扫描

### v3 — 第一版 APK（18:00-18:52）
- busbox wget 绕过 CTA HTTP 拦截
- 后台线程 + Handler 防止 ANR
- `pageLoaded` 标记防止 JS 注入时序错误
- AIOSLOG tag 踩坑：AIOS 用 `AIOSLOG` tag 打日志，内容带 `AIOBD##`

### v2 — 初始 APK（17:51）
- HudDashboard APK 首次上线
- logcat AIOBD## 解析 → OBD 数据显示

### v1 — Pi 代理 + 旧 OBD 桥（2026-07-07~2026-07-11）
- Pi `obd_bridge.py` → V-LINK蓝牙OBD → HA REST API（OBD口被HUD占后退役）
- Pi `hud_obd_proxy.py` → ADB logcat → JSON
- 8T 已退役

---

## 5. 常见故障与诊断

### 5.1 HUD 白板 / 无数据

**排查步骤：**
```bash
# 1. ADB 通吗？
adb devices
# → HUD IP 可能漂移了，扫描 <LAN_IP>-125

# 2. AiOS 有输出吗？
adb shell logcat -s AIOSLOG:* | grep AIOBD

# 3. Pi 代理活着吗？
curl http://<LAN_IP>:8080/obd

# 4. 重启 APK
adb shell am force-stop com.vox.hud
adb shell am start -n com.vox.hud/.MainActivity
```

### 5.2 PI 图标闪烁 / connected 跳变（已修复）

**根因：** logcat 有时无 AIOBD## 行 → 代理设 connected=0
**修复（v6）：** APK 混合模式，本地 logcat 连通即 `piOn=true`

**如果仍有问题：** 检查 HUD 本地 su logcat 权限
```bash
adb shell su -c logcat -s AIOSLOG:* | head
# 若卡住 → Magisk 弹窗需确认（HUD无触摸屏）
```

### 5.3 8888 遥控器不可用

**根因：** 通常 HUD IP 漂移导致 ADB 断连
```bash
# 重启 (自动重扫 IP)
ssh pi@<LAN_IP>
bash ~/hud_remote/start.sh
```
**注意：** server.py 已改为 `_find_hud()` 自动检测，但 start.sh 仍有用。

### 5.4 户外电源 / 燃油不显示

**根因：** `hud_obd_proxy.py` 的 HA_TOKEN 失效或 Authorization 头错误
```bash
# 测试 HA 连通
ssh pi@<LAN_IP>
python3 -c "
import urllib.request, json, os
t=json.load(open(os.path.expanduser('~/ha_config.json')))['token']
r=urllib.request.urlopen(urllib.request.Request(
    'http://<TS_IP>:8123/api/states/sensor.cadillac_battery',
    headers={'Authorization': 'Bearer '+t}), timeout=5)
print(json.load(r)['state'])
"

# 检查代理日志
tail -20 /tmp/hud_proxy.log | grep -i "ha\|token\|error"
```

### 5.5 里程数不对（显示 4xxx）

**根因：** 本地 logcat 的 `行驶里程` 是米单位，APK 直接显示 4176 → 4167km 错误
**修复（v6）：** 合并逻辑优先 Pi 代理的公里值（30607）
- 如果仍显示 4xxx → Pi 代理未运行或 HUD 无网络连接

### 5.6 编译 OOM（拉取 JDK21 时）

**根因：** task-runner 的 `ulimit -v 1GB` 限制与 JDK CompressedClassSpaceSize 冲突
```bash
# ❌ 不行
source task-runner.sh && launch_t build 120 bash build.sh

# ✅ 正确
cd ~/jarvis/hud-apk && bash build.sh
```

---

## 6. 管理命令速查

### 6.1 APK 管理

```bash
# 构建
cd ~/jarvis/hud-apk && bash build.sh

# 推送到 Pi（中转）
scp dist/HudDashboard.apk pi@<LAN_IP>:/home/<USER>/

# 从 Pi 安装到 HUD
ssh pi@<LAN_IP>
adb -s 192.168.0.XXX:5555 install -r /home/<USER>/HudDashboard.apk

# 重启 APK
adb shell am force-stop com.vox.hud
adb shell am start -n com.vox.hud/.MainActivity

# 设为默认桌面
adb shell cmd package set-home-activity \
    --user 0 com.vox.hud/.MainActivity
```

### 6.2 Pi 服务管理

```bash
# OBD 代理 (:8080)
ssh pi@<LAN_IP>
fuser -k 8080/tcp    # 停止
cd && python3 -u hud_obd_proxy.py \
    &>/tmp/hud_proxy.log &

# 8888 遥控器
bash ~/hud_remote/start.sh

# 查看日志
tail -f /tmp/hud_proxy.log

# 测试代理数据
curl -s http://localhost:8080/obd

# ADB 状态
adb devices
```

### 6.3 HUD 连接

```bash
# 扫描 HUD IP（DHCP 漂移 <LAN_IP>-125）
for i in $(seq 100 125); do
  adb connect 192.168.0.$i:5555 2>/dev/null
done
adb devices

# 查看 AIOBD 数据流
adb shell logcat -s AIOSLOG:* | grep AIOBD

# APK 日志
adb shell logcat -s HudService MainActivity
```

### 6.4 HA 数据检查

```bash
# 本地查
source tools/ha_utils.sh
ha_state "sensor.cadillac_battery"
ha_state "sensor.cadillac_remaining"
ha_state "sensor.hud_obd_fuel_level"
ha_state "sensor.onstar_fuel_level"

# Pi 上查
ssh pi@<LAN_IP>
python3 -c "
import urllib.request, json, os
t=json.load(open(os.path.expanduser('~/ha_config.json')))['token']
for e in ['cadillac_battery','cadillac_remaining','hud_obd_fuel_level']:
    r=urllib.request.urlopen(urllib.request.Request(
        'http://<TS_IP>:8123/api/states/sensor.'+e,
        headers={'Authorization': 'Bearer '+t}), timeout=5)
    d=json.load(r)
    print(f'{e}: {d[\"state\"]} ({d.get(\"last_updated\",\"?\")})')
"
```

---

## 7. APK 构建与部署

### 7.1 一次构建

```bash
cd ~/jarvis/hud-apk/
bash build.sh          # ~2min，自动下载工具链
# 输出: dist/HudDashboard.apk (1.5M)
```

**依赖：** `curl`, `unzip`, `java` (JDK 8+), `python3`, `zip`, `zipalign`, `apksigner`（aapt/build-tools自动下载）

### 7.2 部署

```bash
# SCP到Pi
scp dist/HudDashboard.apk pi@<LAN_IP>:/home/<USER>/

# 安装
ssh pi@<LAN_IP>
adb connect 192.168.0.XXX:5555
adb install -r /home/<USER>/HudDashboard.apk

# 重启服务
adb shell am force-stop com.vox.hud
adb shell am start -n com.vox.hud/.MainActivity
```

### 7.3 回滚

```bash
# 存档在 archive-before-hybrid/
# 替换源码后重新构建
cp archive-before-hybrid/HudService.kt \
   app/src/main/java/com/vox/hud/HudService.kt
bash build.sh
```

---

## 8. 已知待办

- [ ] `~/hud_remote/start.sh` → Pi systemd service（8888重启不恢复）
- [ ] `hud_obd_proxy.py` → Pi systemd service（8080重启不恢复）
- [ ] 方向传感器 `HEADING_OFFSET=315°` 需要路上实测校准
- [ ] `TYPE_ORIENTATION` 已废弃（API 27+），长期改用 `TYPE_ROTATION_VECTOR`
- [ ] 车WiFi路由器 DHCP 静态绑定 HUD MAC（防止IP漂移）
- [ ] APK 方向传感器改 `TYPE_ROTATION_VECTOR` + `SensorManager.getRotationMatrix()` / `remapCoordinateSystem()` + `getOrientation()` 以获取真实融合航向
- [ ] `ObdData.kt` 的 `fromJson()` 简单 JSON 解析器在字段含引号时可能崩溃
- [ ] `dashboard.html` 的 compass 显示在 heading=-1 时仍显示 "---"，但指南针已显示0°
- [ ] Pi 代理的 heading 时钟模拟数据（`(time*10)%3600/10`）当 APK 方向传感器失败时会显示（待移除）

---

*文档生成时间：2026-07-12 23:15*
*生成依据：7月12日全天HUD调试日志*
