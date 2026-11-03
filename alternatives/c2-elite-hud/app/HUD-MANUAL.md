# 车萝卜 C2-Elite HUD 系统手册

> 最后更新: 2026-07-12 18:53
> 维护者: Vox 禾笙

---

## 一、系统架构图

```
┌─────────────────────────────────────────────────────┐
│                  凯迪拉克 XT5                        │
│  ┌──────────────────────────────────────────────┐   │
│  │ OBD-II (直插)                                │   │
│  │     │ CAN Bus                                 │   │
│  │     ▼                                         │   │
│  │ 车萝卜 C2-Elite HUD (<LAN_IP>)           │   │
│  │  ┌──────────────┐   ┌──────────────────┐     │   │
│  │  │ AIOS 服务     │   │ HudDashboard APK │     │   │
│  │  │ (AIOBD##log)  │   │ (com.vox.hud)   │     │   │
│  │  └──────┬───────┘   └────────┬─────────┘     │   │
│  │         │                    │ busybox wget   │   │
│  │         │ ADB:5555           │ :8080/obd      │   │
│  └─────────┼────────────────────┼───────────────┘   │
└────────────┼────────────────────┼────────────────────┘
             │                    │
             │ 车WiFi <LAN_IP>/24
             │                    │
     ┌───────┴────────────────────┴──────────────┐
     │           Pi Zero 2W (<LAN_IP>)      │
     │  ┌──────────────────────────────────┐     │
     │  │ hud_obd_proxy.py (port:8080)    │     │
     │  │  ├── ADB → logcat AIOSLOG       │     │
     │  │  ├── 解析 AIOBD## → JSON        │     │
     │  │  └── HA REST API (户外电源)      │     │
     │  └──────────────────────────────────┘     │
     │  SSH: ssh pi@<TS_IP> (Tailscale)    │
     │       ssh pi@<LAN_IP> (车WiFi)      │
     └──────────────────────────────────────────┘
                        │
                        ▼
              ┌──────────────────┐
              │  HA (HomeAssistant)│
              │  <TS_IP>:8123│
              └──────────────────┘
```

---

## 二、核心组件

### 2.1 HUD Dashboard APK
| 属性 | 值 |
|:----|:----|
| 包名 | `com.vox.hud` |
| 源码 | `jarvis/hud-apk/` |
| 构建产物 | `dist/HudDashboard.apk` (1.4MB) |
| 构建脚本 | `build.sh` (Kotlin + d8 + aapt + zipalign + apksigner) |
| 前端 | `assets/dashboard.html` (480×240 全屏布局) |
| 字体 | `assets/OdibeeSans-Regular.ttf` |

**文件结构：**
```
hud-apk/
├── build.sh                    # 一键构建脚本 (⚠️ set -e 会因D8 OOM中断)
├── build_fix.sh                # 绕过ulimit的构建脚本
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   ├── dashboard.html      # HUD 前端UI
│   │   └── OdibeeSans-Regular.ttf
│   └── java/com/vox/hud/
│       ├── MainActivity.kt     # 全屏WebView + HudService启动
│       ├── HudService.kt       # 后台轮询服务 (1s间隔)
│       ├── ObdDataProvider.kt  # busybox wget 请求Pi代理
│       ├── ObdData.kt          # 数据模型 + JSON序列化
│       └── HudBootReceiver.kt  # BOOT_COMPLETED 自启
├── tools/
│   ├── hud_obd_proxy.py        # Pi端代理脚本
│   ├── android-sdk/            # 构建工具 (自动下载)
│   └── kotlinc/                # Kotlin编译器 (自动下载)
└── dist/
    └── HudDashboard.apk        # 编译产物
```

### 2.2 Pi 代理 (hud_obd_proxy.py)
| 属性 | 值 |
|:----|:----|
| 路径 | `pi@<TS_IP>:/home/<USER>/hud_obd_proxy.py` |
| 端口 | 8080 |
| 端点 | `GET /obd` → 完整JSON数据 |
| 端点 | `GET /health` → 连接状态 |
| 轮询 | 每1秒读HUD logcat + 每15秒刷HA数据 |

**API 返回格式：**
```json
{
  "speed": 0, "rpm": 851, "coolantTemp": 89,
  "voltage": 13.7, "fuelLevel": 69,
  "engineLoad": 29, "throttlePos": 19,
  "intakeTemp": 41, "odo": 2647,
  "ambientTemp": 29, "fuelCons": 0.0,
  "heading": 312.1, "power": 100.0,
  "connected": 1
}
```

### 2.3 数据流转
```
AIOS logcat → ADB → Pi解析AIOBD## → HTTP JSON
                                    ↓
                              HUD APK (busybox wget, 后台线程)
                                    ↓
                              WebView evaluateJavascript("updateData(...)")
                                    ↓
                              dashboard.html 渲染
```

---

## 三、日常维护命令

### 3.1 连接HUD
```bash
# 经由Pi连接HUD (ADB)
ssh pi@<TS_IP>
adb connect <LAN_IP>:5555
adb -s <LAN_IP>:5555 shell
```

### 3.2 构建APK
```bash
# 构建 (需要JAVA_OPTS绕过OOM)
cd ~/hud-apk
rm -rf build dist
mkdir -p build dist build/obj

# 1. 编译Kotlin
JAVA_OPTS="-Xmx512m -XX:CompressedClassSpaceSize=256m" \
  tools/kotlinc/bin/kotlinc \
  -cp tools/android-sdk/platforms/android-27/android.jar \
  -d build/classes.jar -include-runtime \
  app/src/main/java/com/vox/hud/*.kt

# 2. DEX
tools/android-sdk/build-tools/29.0.3/d8 \
  --release --lib tools/android-sdk/platforms/android-27/android.jar \
  --output build/obj build/classes.jar

# 3. 打包资源
tools/android-sdk/build-tools/29.0.3/aapt package \
  -f -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res -A app/src/main/assets \
  -I tools/android-sdk/platforms/android-27/android.jar \
  -F build/unsigned.apk

# 4. 注入DEX
python3 -c "import zipfile; zipfile.ZipFile('build/unsigned.apk','a',zipfile.ZIP_DEFLATED).write('build/obj/classes.dex','classes.dex')"
mv build/unsigned.apk build/with-dex.apk

# 5. 对齐+签名
tools/android-sdk/build-tools/29.0.3/zipalign -f -p 4 \
  build/with-dex.apk dist/HudDashboard-aligned.apk
tools/android-sdk/build-tools/29.0.3/apksigner sign \
  --ks tools/debug.keystore --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out dist/HudDashboard.apk dist/HudDashboard-aligned.apk
```

### 3.3 部署到HUD
```bash
# 1. 传APK到Pi
scp dist/HudDashboard.apk pi@<TS_IP>:/home/<USER>/

# 2. 在Pi上安装到HUD
ssh pi@<TS_IP>
adb -s <LAN_IP>:5555 uninstall com.vox.hud
adb -s <LAN_IP>:5555 install /home/<USER>/HudDashboard.apk
adb -s <LAN_IP>:5555 shell "am start -n com.vox.hud/.MainActivity"
```

### 3.4 启动Pi代理
```bash
ssh pi@<TS_IP>
fuser -k 8080/tcp 2>/dev/null  # 杀旧进程
cd /home/<USER> && nohup python3 -u hud_obd_proxy.py > /tmp/hud_proxy.log 2>&1 &

# 验证
curl http://127.0.0.1:8080/obd
curl http://127.0.0.1:8080/health
```

### 3.5 查看HUD日志
```bash
# 实时logcat
adb -s <LAN_IP>:5555 shell "logcat -s HudService ObdProvider"

# 指定PID
adb -s <LAN_IP>:5555 shell "logcat -d --pid \$(adb shell pidof com.vox.hud)"

# 查看AIOS OBD原始数据
adb -s <LAN_IP>:5555 shell "logcat -d -s AIOSLOG:*" | grep "AIOBD##"
```

### 3.6 更新前端HTML（不重编译）
```bash
# 方式1: 重建APK (上策)
# 修改 assets/dashboard.html 后执行构建步骤3-5

# 方式2: ADB推送文件 (中策—需root)
adb push dashboard.html /data/data/com.vox.hud/files/
# 然后改MainActivity加载路径
```

---

## 四、已知问题 & 解决

### 4.1 构建OOM
**症状：** `Could not allocate compressed class space` | `unable to create native thread`
**解决：** 设置 `JAVA_OPTS="-Xmx512m -XX:CompressedClassSpaceSize=256m"`，或不用task-runner直接exec

### 4.2 Mediatek CTA 拦截
**症状：** HttpURLConnection 崩溃 (CtaHttp ClassNotFoundException)
**解决：** 用 `ProcessBuilder("/system/bin/busybox", "wget", ...)` 绕开Java网络栈

### 4.3 READ_LOGS 权限
**症状：** 本地logcat返回空
**解决：** Pi做ADB代理读取，APK通过HTTP获取

### 4.4 主线程网络阻塞
**症状：** Choreographer skipped frames → ANR
**解决：** `object : Thread() { ... }.start()` 后台线程 + Handler.post回主线程

### 4.5 WebView 加载时序
**症状：** `updateData is not defined`
**解决：** `WebViewClient.onPageFinished` 标记 + dataReceiver检查

---

## 五、HA 相关传感器

| Entity ID | 说明 | 数据源 |
|:---------|:-----|:------|
| `sensor.cadillac_battery` | 户外电源电量 % | HA（BLE 集成） |
| `sensor.cadillac_remaining` | 剩余电量 Wh | HA（BLE 集成） |
| `sensor.cadillac_car_voltage` | 汽车电瓶电压 | HA（BLE 集成） |
| `sensor.onstar_fuel_level` | 燃油量 % | OnStar 手机tray.db |
| `sensor.xt5_obd_connected` | OBD连接状态 | Pi OBD桥 (v4) |

---

## 六、构建依赖 (自动下载)
- Kotlin 1.9.20 compiler (`tools/kotlinc/`)
- Android SDK platform 27 (`tools/android-sdk/platforms/android-27/`)
- Build tools 29.0.3 (`tools/android-sdk/build-tools/29.0.3/`)
- debug.keystore (`tools/debug.keystore`)

---

## 七、更新日志

| 日期 | 变更 |
|:----|:-----|
| 2026-07-12 | HUD Dashboard 首次上线，全栈数据流打通 |
