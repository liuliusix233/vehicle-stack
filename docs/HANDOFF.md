# 存档与续做说明（2026-11-04）

> 这一版是**技术档案快照**，不是产品发布。
> 目的：以后想继续维护时，不用重新翻聊天记录，从这里接上。

## 1. 当前状态

| 项 | 值 |
| --- | --- |
| 仓库 | https://github.com/liuliusix233/vehicle-stack |
| Tag | `v0.1.1`（无 Release、无 APK，只有源码） |
| 适用 | 凯迪拉克 CUE 5.0–6.0 **大连屏**车机（SA8155P / Android 12） |
| 验证车 | XT5 2025（开发车），CT5 同平台未实测 |
| 状态 | 代码在、文档在、实拍在；未做干净环境复现 |

## 2. 已完成

| 模块 | 位置 | 状态 |
| --- | --- | --- |
| 投屏接收 | `software/castsink` | 可用（需自建工具链） |
| Shell 桥 | `software/shellbridge` | 可用，替代 adb |
| VHAL 直读 | `software/headunit-vhal` + `docs/VHAL-22.md` | 22 属性实测 |
| HUD（Pi 条屏） | `software/hud` | 240×960 / fb0 16bpp |
| 电源 BLE | `software/power` | 仅 BLE，已去云端 |
| 自动 AC | `software/power/pi-auto-ac` | 13.2V 开 / 13.0V 关 |
| 中继 / OBD | `software/relay` | 可用 |
| 导航 TBT | `docs/NAV-DATA.md` | 有链路与防卡顿规则 |
| 外壳 CAD | `hardware/cad` | 主体 + v9 旧件 |
| 旧 HUD 方案 | `alternatives/c2-elite-hud` | 已退役，仅参考 |

## 3. 已知缺口

1. **没有通用 APK**：包名/端口/签名都绑这台车，别人必须自己构建。
2. **没做 clean-room 验证**：工具链是现成的，没在全新机器上从零跑过。
3. **sentinel 无源码**：用的是成品 4CH DVR。
4. **7801 无鉴权**：默认只在 Tailscale 内网用。
5. **英文文档只有摘要**（`README.en.md`）。

## 4. 以后继续做，从这里开始

```bash
git clone https://github.com/liuliusix233/vehicle-stack
cd vehicle-stack && cp .env.example .env
bash tools/fetch-toolchain.sh      # JDK17 + Kotlin + 调试签名
bash tools/preflight.sh            # 期望 PREFLIGHT OK
python3 tools/sanitize.py --root . --out /tmp/s.json   # 期望 0/0/0
```

阅读顺序：`docs/HOW-WE-BUILT-IT.md` → `AGENTS.md` → 目标模块。

## 5. 关键决策（别再翻回去推翻）

| 决策 | 原因 |
| --- | --- |
| 不用 adb，全部走 ShellBridge | 车机开不了 ADB，只能 U 盘装 App |
| 去掉云 MQTT | 只保留 BLE，避免协议与法律风险 |
| HUD 用 Pi + fb0，不改原厂仪表 | 原厂 HMI 需平台签名，第三方进不去 |
| 屏幕 240×960（不是 360×960） | 实物订单规格 |
| 自动 AC 看电压 13.2/13.0 | 着车 13.8–14.6V，熄火回落 12.6V |
| C2-Elite 旧方案移入 `alternatives/` | 那是另一台设备，不是车机 |
| 不点名第三方 APK | 避免法律与署名问题 |
| 全部凭证/SN/IP 用占位符 | 公开仓库 |

## 6. 待办（想做时挑）

- [ ] 包名/端口参数化，出通用构建
- [ ] clean-room 复现一次，补 `QUICKSTART` 类文档（这次已验证才写）
- [ ] 英文文档扩写
- [ ] 接气压计（BMP280）提升海拔/坡度精度
- [ ] sentinel 自研（若不再用成品）
- [ ] GitHub Release（有通用构建后再做）

## 7. 备份位置

| 位置 | 说明 |
| --- | --- |
| GitHub `v0.1.1` | 源码快照 |
| 本地 `~/backups/vehicle-stack-2026-11-04.tar.gz` | 含 `.git`，可离线恢复 |
| `C:\Users\hezia\Desktop\vehicle-stack` | 解压好的可读副本 |