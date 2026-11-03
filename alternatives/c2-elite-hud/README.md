# C2-Elite HUD（旧方案，已退役）

> 这是**另一种 HUD 实现**，2026-10 停用。当前方案是 Pi + 240×960 条屏（`software/hud/`）。
> 保留它是因为里面的**设计参考、亮度控制、数据桥接思路**仍然有参考价值。

## 设备

| 项 | 值 |
| --- | --- |
| 型号 | 车萝卜 C2-Elite（`mt5873_cd`） |
| 面板 | 480 × 240 |
| 系统 | Android（可开 ADB） |
| 状态 | 2026-10 退役 |

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | HUD APK 源码（Kotlin + WebView 前端） |
| `pi-bridge/hud_gps_bridge.py` | Pi 侧：`adb logcat -s HudGPS` 读定位 → Haversine 累计里程 → HA |
| `app/hud-design-refs/` | 版式研究、主题迭代、原型脚本 |

## 与当前方案的区别

| | C2-Elite（本目录） | Pi 条屏（`software/hud/`） |
| --- | --- | --- |
| 面板 | 480 × 240 | 240 × 960（竖） |
| 渲染 | Android View / WebView | PIL 直写 `/dev/fb0` |
| 数据源 | `adb logcat` + OBD | Location + VHAL + OBD |
| 部署 | U 盘装 APK（设备有 ADB） | Pi 上 systemd |
| 延迟 | 受 App 刷新与 logcat 影响 | <50ms |

## 为什么换掉

1. 面板只有 480×240，信息密度不够（当前方案 240×960，纵向能放三栏）。
2. 渲染依赖 Android App + WebView，改版式要重新打包安装。
3. 数据靠 logcat 中转，链路长、易断。

## 复用时注意

- **这是 C2-Elite 的 App，不是车机 App**，别装到 SA8155P 上。
- `pi-bridge/hud_gps_bridge.py` 依赖 ADB，只在有 ADB 的设备上有意义。
- 设计参考（`app/hud-design-refs/`）是公开资料的研究笔记，可以直接看版式。