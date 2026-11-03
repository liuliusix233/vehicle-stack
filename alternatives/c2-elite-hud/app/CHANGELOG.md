# HudDashboard Changelog

## auto_brightness v6（脚本；未部署）· 2026-10-26
- 现象：HUD 亮度偶发"眨眼睛"——忽然暗一下再回亮，频率不高
- 根因（推断，待 HUD 侧验证）：v5 不对称策略「变暗立刻 / 变亮等 8s」，ALS 遇瞬时阴影 → 立即压暗，8s 后回亮
- v6：变暗改 2s 确认、两次调整最小间隔 3s、所有变化 6 步 ≈0.4s 渐变；保留 8s 变亮确认与夜间低亮偏好
- 状态：**已写未部署**。车机 adb 5555 不可达；且盒子指出 HUD 与车机是两套设备，落地前需确认正确目标（部署脚本指向 HUD <LAN_IP>）

## 1.22 · 内网 2000E AC 状态指示 (2026-10-21, versionCode 29)
- 主题 A 顶栏 PI 徽标右侧新增同尺寸 `AC` 徽标（绿色=AC 开 / 灰=关），逻辑与 PI 一致（on 亮 / off 灰）
- 数据源走**车机内网** Pi BLE 桥：`http://<LAN_IP>:8095/state` → `ports["2-1"]`（回退 Tailscale `<TS_IP>:8095`），不走公网 HA
- `ObdSnapshot` 新增 `acOn`(1/0/-1) → JSON → WebView；HA 轮询每 5s 同步，取不到沿用上次值
- 实测：logcat `⚡ 2000E AC=ON (intranet)`；截图 `shots/hud_ac.png` 见 PI 右侧绿色 AC
- 部署：Pi 中转 → HUD <LAN_IP>，`versionName=1.22`，`lastUpdateTime=2026-10-21 01:22:09`
- APK md5 `48d249ebab3061d4c35afdadb880d42e`；assets/dashboard.html md5 `b5303cc5f32a636e37ce9edf447501d7`（内置一致）

## 1.19 · 兼容 AIOBD 新日志格式 StatusInfo (2026-10-19, versionCode 26)

### 🔧 修复速度/RPM/油耗不显示
- AIOBD 当前输出 `AIOSLOG : MainScreenFragment##update,StatusInfo{...}`，旧的 `AIOBD##` 批格式已消失
- `LocalLogcatReader` 新增 `StatusInfo{` 分支，逐行解析；保留旧 `AIOBD##` 解析作兼容
- `ObdSnapshot.fromStatusInfo()` 映射：`mObdSpeed`（回退 `mCurrentSpeed`）→ speed，`mRotateSpeed` → rpm，`mOilWear` → fuelCons，`mDirection` → heading
- 未在 StatusInfo 中出现的字段（水温/电压/剩余油量）保持 -1，不伪造

## 1.18 · ClusterDashboardActivity 仪表屏投屏 (2026-10-19, versionCode 25) — ⚠️ 已回退

> **⚠️ 当日回退（2026-10-19）**：此方案把 HUD 的 dashboard.html/MainActivity UI 复制到仪表屏，属照搬 UI，方向错误。已删除 `ClusterDashboardActivity.kt`，Manifest 恢复 1.17/vc24 并重新构建。仪表屏渲染改由 ShellBridge 原生 DashboardActivity（自绘 Canvas，订阅 HUD 广播）承担，HUD 只作数据源。详见 `docs/cluster-cast.md`。以下保留原文供历史查阅。

### 📺 新增 ClusterDashboardActivity：ShellBridge 可 `am start --display` 在仪表屏直接拉起 dashboard（不经手机、无视频流）
- **新增** `ClusterDashboardActivity.kt`：独立 Activity（launchMode=standard、exported=true、configChanges 含 screenSize/smallestScreenSize/density/uiMode），复制 MainActivity 的 WebView 初始化 / dashboard.html 加载 / HudService.ACTION_UPDATE 广播接收 / `injectJs("updateData(...)")` / 全屏+常亮逻辑
- **远程关闭**：注册 `com.vox.hud.ACTION_FINISH_CLUSTER` 广播 → `finish()`（ShellBridge `#CLUSTER STOP` 发此广播）
- **差异**：BACK 键关闭本实例（MainActivity 吞 BACK）；无 LAUNCHER filter，仅显式组件启动
- **Manifest**：versionCode 24 → 25，versionName 1.17 → 1.18
- **配套**：ShellBridge 2.1/vc11 新增 `#CLUSTER LIST/START/STOP/PROBE`，架构与用法见 `docs/cluster-cast.md`
- **未验证**：车机离线，未实机安装（仅构建验证：build.sh 通过 + aapt badging 确认）

## 1.17 · 三页速度居中 + 车辆电池总电量 (2026-10-18, versionCode 24)

### 🎯 B/C/D 速度居中统一 + 档位右移 + 电量改用车辆电池总电量（A 不动）
- **速度居中**：每页速度改为固定宽度居中容器（B 40vw 屏幕中心 / C 左列 33vw 内 26vw 居中 / D 42vw 屏幕中心），数字 `text-align:center`；档位绝对定位在容器右缘外侧（B/D +1.5vw，C +0.8vw），KM/H 在档位上方，2/3 位数中心不随位数变；字号保持 B 22vw / C 22vw / D 26vw
- **DOM 实测**（640×320@2x，87 vs 128 中心差）：B 320.0/320.0（Δ0px）、C 153.59/153.59（Δ0，左列中心）、D 319.99/319.99（Δ0）；档位 left 恒定（B 457.59 / C 241.89 / D 463.98），C 档右缘 294.89 < 右列 307.2 不重叠，无裁切
- **电量数据源**：HudService.kt HA 实体数组首项 `sensor.cadillac_battery`（户外电源）→ `sensor.vehicle_battery_total`（车辆电池总电量，索引不变），`power` 即车辆电池总电量，B/C/D 电量显示自动跟随；C/D 标签 `STATION`→`VEHICLE`（D mini 条 `STA`→`VEH`，告警 LOW STATION→LOW VEHICLE），B 保留 `BAT`
- **Manifest**：versionCode 23 → 24，versionName 1.16 → 1.17
- **截图**：`shots/preview_b87.png` `shots/preview_b128.png` `shots/preview_c87.png` `shots/preview_c128.png` `shots/preview_d87.png` `shots/preview_d128.png`
- **部署**：`build.sh`(direct shell) → Pi <TS_IP> 中转 → HUD **<LAN_IP>**（C2-Elite；install 后 adb 抖动，kill-server+重连恢复），`lastUpdateTime=2026-10-18 10:10:41`，pid 12296
- **APK md5**：`239f4767cc87b0a7997509f142f0f6b5`（APK 内 assets/dashboard.html `3a9294e9f62e7847809c513a8b3c88de` 与源文件一致）

## 1.16 · assets-only refresh (2026-10-18, versionCode 23)

> versionName 仍为 1.16（versionCode 23），本次仅更新 assets，未改 Kotlin/Manifest。

### 🎨 主题套件：C 能源表盘 + D 夜航 RPM + B 可读性重排（A 不动）
- **C 能源表盘**（12V/STATION/FUEL 三能量 + RPM）、**D 夜航加 RPM**、**C/D 字号与条高统一**（2026-10-17 完成，本次随包发布）
- **B 可读性重排**（对齐 A 标准，保持纯黑+大速度+ODO+RPM 渐变身份）：速度 20→22vw、档位 3.33→4vw；全部标签/状态文字 ≥2.2vw（power/sys/elbl/rpmlbl/volt 2.08→2.2vw）；主数值 ≥3vw（FUEL/BAT 值 2.5→3vw，ODO 2.08→3vw）；FUEL/BAT 条高 0.83vw→6vh；RPM 渐变条 1.25vw→3.5vh；CO2 条 0.7→1.6vh；状态点 0.83→1vw
- **排版**：底部左列两行能量条变粗后纵向重排（center 18.75→16vh / 50→46vh，能量行距 2.08vw→1.6vh），不压 ODO/屏幕边缘；右列 RPM 头与 3.5vh 条对齐（行距 1.67vw→1vh），VOLT 不贴边；顶栏 pill/PI/NANO/罗盘/时钟保持一行无换行
- **验证**：640×320@2x 渲染（speed 87/rpm 1900/fuel 62/power 78/13.8V/odo 39412.3/312°/co2 620）无重叠无裁切 → `shots/preview_b87.png`；DOM 实测 speed 140.8px(22vw)、RPM 条 11.19px(3.5vh)、FUEL/BAT 条 19.19px(6vh)、标签 14.08px(2.2vw)、数值 19.2px(3vw)
- **部署**：`build.sh`(direct shell) → Pi <TS_IP> 中转 → HUD **<LAN_IP>**（C2-Elite，install 首次 adb 抖动，disconnect+sleep 重试成功），`lastUpdateTime=2026-10-18 09:59:32`，pid 7859
- **APK md5**：`7ce680c208c71b2fe615492bbd394cbc`（APK 内 assets/dashboard.html 与源文件一致）

## 1.16 (2026-10-06, versionCode 23)

### 📏 里程刻度补偿 ODO_SCALE = 1.0045（一劳永逸，替代周期性校准）
- **背景**：HUD 里程反复比仪表盘少（最近 3.275km / 自 09-23 跑了 963km），此前靠周期性 CALIBRATE 补差
- **排查结论**：① 棘轮**从未触发**（9/07、9/10、9/23 三份抓包 + 实时 logcat 全 0 条 `Odo ratchet`；且棘轮只挡回落→只会偏高，不可能造成“变少”）；② 根因是 **AIOBD「行驶里程」累计值相对车机仪表盘系统性少计 ~0.45%**
  - 6 个校准点 (AIOBD km, 仪表盘 km) 线性回归 slope = **1.0045**，残差 <0.8km
  - 08-16 (7872.693, 38542.406)｜09-01 (8422.538, 39095.0)｜09-07 (8701.063, 39374.023)｜09-10 (8950.274, 39625.234)｜09-23 (9422.120, 40099.580)｜10-06 (10385.126, 41065.86)
- **改动** (`HudService.kt`)
  - 新增 `private const val ODO_SCALE = 1.0045f`
  - `总里程 = odoBase + aiobdOdo * ODO_SCALE`（原 `odoBase + aiobdOdo`）
  - 无显式基数的 CALIBRATE 同步改：`odoBase = calOdo − aiobd * ODO_SCALE`（**显式基数分支不变**，仍可直接覆盖）
  - 单向棘轮逻辑不动（仍按 `total` 判增）
- **重锚**：`CALIBRATE --ef odo 41065.86 --ef odoBase 30634.002`（= 41065.86 − 1.0045×10385.126），保证切模型瞬间里程连续
- **Manifest**：versionCode 22 → 23，versionName 1.15 → 1.16
- **同步**：`tools/hud_ha_bridge_nano.py` ODO_BASE → 30634.002 + 新增 ODO_SCALE=1.0045（HA 桥，当前未运行）
- **验证**：HUD 真机 `dumpsys package com.vox.hud` versionName=1.16；prefs `last_good_odo=41065.86 / odo_base=30634.002`；logcat `CALIBRATE: explicit base=30634.002` + `Broadcast merged: odo=41065.86`
- **部署**：`build.sh`(direct exec) → scp Pi `/home/<USER>/hud_new.apk` → adb push + `pm install -r` → force-stop/start（HUD IP <LAN_IP>）
- ⚠️ 预期：此后 AIOBD 按 1.0045 走，残余误差 <1km；若仍飘，照旧 CALIBRATE（显式基数）覆盖

## 运行时校准 (2026-09-07, versionCode 22 未重推)
- **里程 +1.5km（两次）**：39373.5→39375.0（基数 30672.46→30673.96），CALIBRATE intent 运行时校准，已持久化到 HUD odometer.xml（00:19 +0.5 至 39374.0；00:28 +1km 至 39375.0）
- 未改源码/未重推 APK；Nano bridge 本地副本同步 30673.96，待 Nano 上线推送重启
- 期间 HUD IP 漂移 105→106（重连后校准）

## 1.15 (2026-08-23, versionCode 22)

### 🐛 真机修复：罗盘汉字方框 + 无数据布局紧凑/重叠（盒子反馈）
- **罗盘汉字方框**：新面板字体栈全是西文字体（-apple-system/Helvetica/Arial），中文无兜底 → 豆腐块。修复：字体栈加 `Noto Sans CJK SC`/`Droid Sans Fallback`/`PingFang SC`/`Microsoft YaHei`，罗盘元素单独强调中文字体
- **无数据时紧凑/重叠**：新面板全用 flex `gap`，但 HUD WebView **Chrome 61 不支持 flex gap**（Chrome 84+ 才有）→ 真机 gap 全失效，无数据时（值窄）元素贴紧甚至重叠；有数据时值宽不明显
  - 修复：全部 flex gap → margin（.nb-left/.nb-power svg/.nb-sys dot/.nb-compass::before/.nb-center/.nb-meta/.nb-leftcol/.nb-energy/.nb-rightcol/.nb-rpmhead/.nb-gauges）
  - 验证：Playwright 强制 gap=0 模拟 Chrome 61 三状态（empty/parked/full），元素间距与新版渲染一致，无贴紧无重叠

## 1.14 (2026-08-23, versionCode 21)

### 🏁 Theme B 重做：纯黑 + ODO（盒子新面板，替换运动仪表版）
- **布局**（全部 vw/vh 相对单位，Chrome 61 安全）：顶栏（电源输入功率框 | PI/NANO 状态点 | 罗盘方向+角度 | 时钟）/ 中央大时速 20vw + 档位（D+N，红斜切框）/ 底部左（FUE 白条 + BAT 蓝条 + ODO）/ 底部右（ENGINE RPM 数值 + 青白红渐变条 + VOLT）
- **数据一一对接**：电源输入功率 ← `st.chargePower`（HA `sensor.cadillac_charge_power`，HudService 每 5s）；BAT ← `st.power`（DJI 电量%）；FUE/RPM/VOLT/时速 ← 本地 AIOBD；ODO ← hud-odo 校准；罗盘 ← GPS bearing；档位 ← 齿比推算
- **删假数据模拟**：原面板 setInterval 模拟加减速移除，改 `updateData(st)` 驱动 + CSS transition 平滑（无 rAF，防 CPU 峰值）
- **Theme B 下隐藏 voice 覆盖层**（右下角 mic 图标会压 VOLT）；时钟在 renderThemeB 同步刷新
- Theme A 零改动，遥控器左右键切换保留

## 1.13 (2026-08-18, versionCode 20)

### 🏎️ Theme B 重做：运动仪表（遥控器左右键切换）
- **布局**：纯黑底，中央大时速（21vw）；上方航向箭头+方向+时钟+WIFI/PI/NANO 状态；下方档位框+转速（千百两位，÷100）；底部 7 格信息行（ODO/EH/SRV/IN·OUT/ALT/SLP/V）不删减
- **时速变色**：颜色由发动机输出百分比（engineLoad，本地 AIOBD「发动机负荷」）1-100% 蓝→红渐变（hue 220°→0°），无数据时白色
- **两侧半弧**：左 FUEL（橙，<15% 变红）/ 右 BAT（青，户外电源电量%，<20% 变红），SVG stroke-dashoffset 填充，从底部升起
- **转速联动**：转速↑/负荷↑ → 弧半径↑（50→78px，弧整体远离时速）、时速数字微放大（scale 1.0→1.05）；自停 rAF 平滑（~30fps，收敛即停，不烧 CPU）
- **数据链路**：ObdData.kt toJson 新增 `engineLoad`/`throttlePos`（原已解析未透传）
- **Theme A（原仪表）零改动**；切主题后新主题不依赖 Pi proxy（纯本地 AIOBD 数据）
- 构建部署：Pi 中转 adb install → HUD <LAN_IP>:5555（IP 又漂了）

### 🐛 v1.13 hotfix（当晚，真机崩盘修复）
- **症状**：真机 Theme B 全崩：时速巨大、半弧变乱线（蓝色 V 形）、大小位置不一
- **根因 1**：几何硬编码 px（cy=120/bowL=118/r=50-78），真机 CSS viewport≠480×240（density 120 → CSS 宽 640）→ vw 字体正常但 px 元素错位
- **根因 2**：SVG `pathLength`（SVG2）在 HUD WebView **Chrome 61** 不支持 → dasharray 按 100px 计算 → 弧线碎成乱段
- **修复**：全部几何改 `vw()/vh()` 相对单位（基于 innerWidth/Height）；弧线填充改 JS 三角函数直接生成 path d（`arcPathR/arcPathL`，pct<0.5 返回空路径），弃用 pathLength/dashoffset；stroke-width 6px→1.25vw
- **验证**：Playwright 480×240 / 640×320 / 640×360 三尺寸比例一致；真机截图确认（20:53）半弧平滑、无重叠
- ⚠️ Chrome 61 兼容：`pathLength`/`conic-gradient` ❌；`padStart`/`grid`/`hsl()`/`filter` ✓

## 1.10 (2026-08-13, versionCode 17)

### ⚡ 户外电源数据源：公网 HA 直连优先 (Pi proxy 降级回退)
- **背景**: HUD Tailscale/Pi 链路不稳时, 户外电源电量/功率不显示
- **改动**: `fetchHaBattery()` 改为两段式
  1. **公网 HA 直连优先**: `http://nas.<PRIVATE_DOMAIN>:8123/api/states/sensor.cadillac_{battery,charge_power,discharge_power}`，带内置 HA_TOKEN
  2. **Pi proxy 回退**: 车WiFi <LAN_IP>:8080 → Tailscale <TS_IP>:8080 (原逻辑保留)
- **验证**: logcat `⚡ public HA: power=81% in=14W out=4W` (2026-08-13 22:55)
- **安装**: Pi 中转 adb install → HUD <LAN_IP>:5555

## 1.9 hotfix (2026-08-09, versionCode 16)

### 🔑 里程计算重构（第一性原理）
- **废弃 trip delta 累加**：原逻辑 `总里程 = 基线 + AIOBD trip 差值累加`，跨重启需要持久化状态，重启/闪退/ logcat 重放都会导致丢里程或虚增（实测丢 2.7km、装 APK 虚增 1.945km）
- **新模型：`总里程 = odoBase + AIOBD行驶里程`**
  - AIOBD「行驶里程」字段本身就是车机累计值（自安装 HUD 起算，单位 m ÷1000 = km）
  - `odoBase` = 安装 HUD 时的车机里程（默认 30669.312，校准自动反推）
  - 无累加状态、无跨重启记忆 → 重启/闪退零丢失
- **校准语义变更**：`CALIBRATE odo=X` 反推 `odoBase = X − 当前AIOBD值`（原：直接设 lastGoodOdo + 重置 tripBaseline）

### 🐛 Bug 修复
- **resetForNewService 不归零 bug**：只清了 prefs 没清内存累加器 `totalMs/tripMs/lastTickMs` → 引擎小时重置后从旧值续涨（246.88h）。补清 + `commit()`
- **logcat buffer 重放**：LocalLogcatReader 启动改 `logcat -T <当前时间>`，只读新数据（原 `logcat -s` 会重放历史行 → tripBaseline 被污染 → 虚增里程）
- **persistOdo 异步写盘**：`apply()` → `commit()` 同步落盘
- **broadcastMerged 单帧异常杀服务**：整体 try/catch，一帧坏数据不再中断里程累计

### 🛡️ 防闪退丢数据
- 全局 `UncaughtExceptionHandler`：崩溃前同步抢救 odo + engHrs 再死
- `odoPersistTask`：每 5s 强制持久化（磁盘状态永远 ≤5s 新鲜）

### 🎨 UI 改版
- **删实时油耗（L/100）**，改为显示 **户外电源实时输入/输出功率（IN/OUT, W）**
- ObdSnapshot 新增 `chargePower` / `dischargePower` 字段
- fetchHaBattery 解析 IN/OUT 功率，多地址回退（车WiFi <LAN_IP> → Tailscale <TS_IP>）
- Pi 代理 `hud_pi_proxy.py` ha_data() 新增 `chargePower` / `dischargePower`（HA 直读 sensor.cadillac_charge_power / discharge_power）

## 1.7.5 (2026-07-21)
- **免root改造**: 去掉所有 `su -c` 调用（LocalLogcatReader / Nano ping）
  - logcat 改用直接调用（不需 root）
  - Nano 探测改用 Java Socket TCP（Port 22, 2s超时）
- **EngineHourCounter race condition 修复**: `calibrateHours()` 增加 `totalMs` 同步，防止下次 tick() 覆盖校准值
- **里程校准**: 通过 ADB `am startservice` 校准 odo/engHrs

## 1.7.4 (2026-07-21)
- 首次免root尝试（漏了 engineHour 同步 bug）

## 1.7.3 (2026-07-20)
- 优化里程累加逻辑，Pi代理里程校准

## 1.7.2 (2026-07-19)
- NANO标签、引擎小时计数器

## 1.7.1 (2026-07-17)
- 语音助手集成

## 1.7.0 (2026-07-16)
- 混合模式v7，HA direct provider

## 1.6 (2026-07-15)
- 引擎小时计数器（EngineHourCounter）

## 1.5 (2026-07-14)
- 里程 trip delta 累加，GPS/磁航向
