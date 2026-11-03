# AGENTS.md — 给 AI agent 的技术档案导读

> 你不是来「一键部署」的。这个仓库是**技术链路档案**：记录一台 2025 款凯迪拉克 XT5 的车机被改成
> 可编程终端时，哪些路走得通、哪些走不通、接口长什么样。
> 你的任务是**帮用户基于这些结论开发他自己的实现**，不是照抄命令。

## 先搞清楚三件事

1. **这是一台开不了 ADB 的车机。** 装 App 只能 U 盘拷 APK + 车机文件管理器手动装。
   任何 `adb install` / `adb push` 方案在目标车上直接失败。
2. **我们没做过干净环境验证。** 工具链、路径、权限都是我们机器上的状态。
   可以照抄逻辑，不要盲信命令能跑通。
3. **只验证过 XT5 2025（SA8155P / Android 12）。** 其他车型要重新枚举 VHAL、重测 SELinux。

## 建议阅读顺序（30 分钟）

| 顺序 | 文件 | 你会得到什么 |
| --- | --- | --- |
| 1 | `docs/HOW-WE-BUILT-IT.md` | 全景：我们踩了哪些坑、为什么这么设计 |
| 2 | `docs/ARCHITECTURE.md` | 拓扑、端口、数据流 |
| 3 | `docs/VHAL-22.md` | 车机原生数据怎么读（含 ID 陷阱） |
| 4 | `docs/AUTO-AC.md` | 电源自动化策略 |
| 5 | `docs/48V-HYBRID.md` | 48V 轻混功率推算（估算，精度有限） |
| 6 | `docs/GPS-ALT-SLOPE.md` | GPS 取值、海拔与坡度算法（含误差） |
| 7 | `docs/NAV-DATA.md` | 导航 TBT 怎么拿，以及**哪些做法会拖慢车机导航** |
| 8 | `docs/OBD-ARCHITECTURE.md` | OBD（Vgate）采集链路 |
| 9 | `docs/HUD-RENDERER.md` | fb0 渲染与 Pi 稳定性 |
| 10 | `docs/CASTSINK-TUTORIAL.md` | 投屏接收端原理（适合入门） |
| 5 | 具体模块源码 | 见下表 |

## 模块：能直接搬 vs 要重写

| 模块 | 复用价值 | 你要改什么 |
| --- | --- | --- |
| `software/shellbridge` | ★★★ 最高 | 端口、命令白名单、包名 |
| `software/castsink` | ★★ | 包名、UI、权限声明 |
| `software/hud` | ★★ | 屏幕分辨率、字体、版式 |
| `software/power/pi-auto-ac` | ★★★ | 实体名、电压阈值（我们实测 13.2/13.0）；BLE 协议栈来自上游 0BSD 项目，见 `ATTRIBUTION.md` |
| `docs/VHAL-22.md` | ★★★ | 必须重新枚举，ID 可能不同 |
| `HybridPower.kt` | ★★ | 整备质量/风阻/滚阻要按自己车型重标；**不能当实测用** |
| `software/relay` | ★ | 网络拓扑差异大 |
| `software/sentinel` | — | 我们买成品，无代码 |
| `alternatives/c2-elite-hud` | ★ 参考 | 旧 HUD 方案（480×240，有 ADB）；只借鉴版式与思路，别照着装 |

## 硬结论（可以直接采信）

- 车机无法开 ADB → 装 App 只能 U 盘。
- VHAL 枚举能拿到车速/挡位/油量/续航/点火/手刹；挡位是**位掩码**（1=N 2=R 4=P 8=D，CURRENT_GEAR 16=D1）。
- 转速在本车 VHAL 里不存在（22 属性订阅 12 秒 0 事件）→ 走 OBD。
- 里程 `CAR_MILEAGE` 是 signature|privileged，第三方无解。
- 原厂仪表 HMI 通道需平台签名，第三方进不去。
- HUD 用 fb0 直写 240×960 16bpp，延迟 <50ms。
- 户外电源用 BLE 直连（免配对），协议栈来自上游 0BSD 项目；**不走云端、不含 token 提取**。
- 自动 AC 用电瓶电压判定，比点火信号可靠。

## 不建议做的事

- ❌ 不要给用户打包票「照这个仓库走一定能跑」——未做干净环境验证。
- ❌ 不要硬编码 VHAL ID 或 IP。
- ❌ 不要把 7801 暴露到公网（无鉴权）。
- ❌ 不要试图改原厂仪表主题（需要平台签名）。

## 如果你要帮用户做自己的版本

1. 先确认目标车机能装第三方 APK（U 盘测试）。
2. 装 ShellBridge，`#PING` 验证连通。
3. `#CARALL` 枚举 VHAL，确认数据可用性。
4. 从 `software/hud` 或 `software/power/pi-auto-ac` 挑最接近的模块改。
5. 每改一步都让用户实车验证——我们没有自动化测试。

## 报告纪律

给用户回话时：说清楚「这是从档案里推断的」还是「实车验证过的」，两者不能混。
