# 架构

## 拓扑

```
  手机 / 电脑                        家里
  ┌──────────┐                  ┌──────────────────┐
  │ 投屏源   │ ── Wi-Fi ──┐     │  HA (8123)       │
  └──────────┘            │     │   ├ 电源实体     │
                          ▼     │   └ 车辆实体     │
  车机 SA8155P (Android 12)      └───────▲──────────┘
  ┌──────────────────────────┐           │ HTTP
  │ CastSink  (收投屏)       │           │
  │ ShellBridge (TCP 7801)   │◄──Wi-Fi──►│
  │ HUD APK / 车机侧前端     │           │
  └──────────▲───────────────┘     ┌─────┴──────────┐
             │ adb / nc 隧道       │ Raspberry Pi   │
             └─────────────────────┤ HUD 渲染 fb0   │
                                     │ relay / OBD   │
                                     └─────▲─────────┘
                                           │ BLE
                                     ┌─────┴─────────┐
                                     │ DJI 户外电源  │
                                     └───────────────┘
```

## 端口

| 端口 | 位置 | 用途 |
| --- | --- | --- |
| 5555 | 车机 | ADB over Wi-Fi |
| 7801 | 车机 | ShellBridge TCP 命令通道 |
| 8080 | Pi | OBD/GPS 代理（HTTP） |
| 8477 | Pi | HUD 安装包分发 |
| 8123 | HA | REST API |

## 控制回路

```
采集: 电源 BLE ─→ Pi ─→ HA 实体
展示: HA / OBD / GPS ─→ Pi fb_dash.py ─→ /dev/fb0 ─→ 条屏
动作: HA 自动化 ─→ ShellBridge(7801) ─→ 车机 shell ─→ 重启/音量/应用
守护: sentinel ─→ systemd timer ─→ 探测 + 重启异常进程
```

## 关键设计取舍

- **不用 Gradle 的地方不用**：ShellBridge 用 `aapt + javac + d8` 纯命令行构建，避免车机厂商策略与 AGP 版本地狱。
- **HUD 直写 framebuffer**：车机不给 system overlay 权限，走 Pi + fb0 最省事，延迟 <50ms。
- **控制面单一入口**：所有远程动作走 ShellBridge 的固定命令白名单，避免开放任意 shell。
- **凭证只落在 .env / config.json**：仓库内全部是占位符，便于公开。