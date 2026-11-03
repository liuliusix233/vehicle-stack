# Changelog

所有改动记录在这里。版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

## [0.1.0] — 2026-11-04

首个公开版本。

### Added
- `software/castsink` — 投屏接收端 + ShellBridge 命令通道
- `software/shellbridge` — 受限车机 TCP shell 桥（纯 Java 构建）
- `software/hud` — 240×960 条屏 HUD（PIL 直写 fb0）
- `software/power` — DJI 户外电源监控（BLE 本地）
- `software/relay` — Pi 中继 / OBD 桥
- `software/headunit-vhal` — 22 属性 VHAL 实测与探针
- `docs/VHAL-22.md` — 车机原生数据接入实测记录
- `docs/NAV-DATA.md` — 车机导航链路与防卡顿规则
- `docs/GPS-ALT-SLOPE.md` — GPS 取值、海拔与坡度算法
- `hardware/CAD.md` + `hardware/cad/` — HUD 外壳 STL 与装配说明
- `docs/OBD-ARCHITECTURE.md`、`docs/HUD-RENDERER.md`、`docs/HUD-MAP-RENDERING.md`、`docs/CARBRIDGE.md`、`docs/CASTSINK-TUTORIAL.md` — 历史技术档案（已脱敏）
- `docs/48V-HYBRID.md` — 48V 轻混功率推算模型（估算，非实测）
- `docs/gallery/` — 实拍验收证据
- `docs/WIRING.md` — 接线示意
- `hardware/BOM.md`、`hardware/INSTALL.md` — 选型与安装
- `docs/HOW-WE-BUILT-IT.md` — 实车复盘（技术链路，不是安装手册）
- `tools/sanitize.py` — 发布前脱敏扫描
- `tools/fetch-toolchain.sh` — 一键拉构建工具链

### Known issues
- `software/sentinel` 依赖成品 DVR，无源码
- 7801 端口无鉴权，默认只在 Tailscale 内网使用
- 仅验证 SA8155P（XT5 2025 开发车）