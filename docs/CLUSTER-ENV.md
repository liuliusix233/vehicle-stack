# 车机环境档案 — SA8155P (GM aegean)

> 采集：2026-10-20 22:47（ShellBridge v2.4 shell + getprop）
> 用途：DIY 仪表盘（复现 com.cadillac.wallpaper 的任意画面投屏）

## 系统
| 项 | 值 |
|---|---|
| model | `aegean` |
| manufacturer | `gm` |
| Android | 12 (SDK 32) |
| ABI | arm64-v8a |
| SoC | Qualcomm SA8155P |
| fingerprint | `gm/aegean_orange_n/aegean:12/VCUSP5-34/67:user/release-keys` |
| ro.sf.lcd_density | 280 |
| persist.sys.clusterCurrentView | 2 |

## 显示屏（EnvServer 实测，2026-10-20 22:51）
| display | 名称 | realSize | densityDpi | rotation | state | mode | supportedModes |
|---|---|---|---|---|---|---|---|
| 0 | 内置屏幕 | 3904 × 1320 | 280 | 0 | 2 (ON) | 3950×1320 @60Hz | 1 |
| 2 | 内置屏幕（仪表） | 3904 × 1320 | 360 | 0 | 2 (ON) | 3904×1320 @60Hz | 1 |

- **仪表屏 = display 2**，物理/逻辑同为超宽 3904×1320，60Hz，密度 360dpi（主屏 280dpi）
- 仪表屏可用 `ActivityOptions.setLaunchDisplayId(2)` 点亮（2026-10-20 22:45 已实测）
- 原始 JSON：`docs/car-cluster-env.json`

## 原版实现路径（逆向结论）
- 参考 APK：`com.cadillac.wallpaper` V4.0.0（23.7MB，md5 `314c54714d2c169f39dc0253c4a3e2c1`）
- 投屏链路：手机 → RTSP/RTP(H.264/H.265) → native sink → MediaCodec → `SharedVideoSession.setOutputSurface` → 仪表屏 Activity 的 SurfaceView
- 仪表 Activity：`com.cadillac.carlink.car.cluster.CarlinkClusterActivity`，onCreate 仅 `setContentView(new SurfaceView(this))`，display id 运行时读 `View.getDisplay().getDisplayId()`
- 双会话上限：`DualDisplayCastPolicy.maxConcurrentCastSessions = 2`
- 报告：`docs/cadillac-apk-full-report.md`

## 车机额外可用的 GM 系统能力（本机实测）
- 服务：`clusterService` → `gm.cluster.IClusterHmi`
- 库：`/system/framework/info3_cluster.jar`（`gm.cluster.ClusterHmiManager`，已拉回 `/tmp/info3_cluster.jar`）
- 声明：`/system/etc/permissions/info3_cluster_library.xml`（库名 `info3_cluster`，app 可 uses-library 链接）
- 主要 API：`uploadBitmap(Bitmap)→String`、`displayScreen(ScreenIdEnum)`、`showScreen`、`displayOverlay/cancelOverlay`、`hideScreen`、`requestVideoFocus/abandonVideoFocus`、`registerScreenChangeCallback` 等
- 屏幕枚举 `ScreenIdEnum`：FUEL_ECONOMY / G_FORCE / TIRE_PRESSURE / OIL_LIFE / TRIP_ODO_AFE / ERROR_SCREEN_ID …
- 组件类型常量：TYPE_C2_ENHANCED_CLUSTER / TYPE_C2_BALANCED_CLUSTER / TYPE_C2_HUD / TYPE_C1_CLUSTER / TYPE_C1_HUD

## 投屏能力实测结论（2026-10-20 22:55）
- ✅ **任意帧投到仪表屏**：JPEG/PNG over TCP（:27184，4 字节长度前缀），画布按 3904×1320 接受，实测落屏
- ✅ **PNG alpha 合成**：透明/半透明像素按 alpha 正确合成；**但窗口背后是黑底**（原生仪表内容不透出），因此 DIY 仪表盘需整幅自绘，不能依赖叠加原生 UI
- ✅ 窗口尺寸/铺满正常（盒子确认"大小正常"，无需再量）
- ⬜ H.264 实时视频流（原版走 RTSP/RTP → MediaCodec → Surface），当前 Activity 用 Canvas 绘制，视频通路待接

## 测试 APK
- `CastSink_v4.apk`（/sdcard/Download，md5 `1918c0d5b86939801d6577993b4245a4`）
  - MainActivity：枚举显示屏 / 拉起 d1-d3 / HMI 探活 / 推测试图 / 屏幕枚举
  - ClusterCastActivity：仪表屏全屏 Surface + JPEG-over-TCP 接收（:27184，4字节长度+JPEG）
  - EnvServer：TCP :27185 连上即回环境 JSON
