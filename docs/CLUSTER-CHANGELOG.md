# 车机仪表盘投屏 — 更新日志

> 项目：CastSink（车机）+ HudDashboard 广播/推流（HUD）
> 日期：2026-10-20

## 结论先行
- 仪表屏 = **display 2**，3904×1320 @60Hz，360dpi
- 拉起方式：`ActivityOptions.setLaunchDisplayId(2)`（`am start --display` 需特权）
- 原生 UI 不透出（窗口背后黑底）→ DIY 仪表必须整幅自绘
- 车机**入站被防火墙全挡**，出站正常 → 车机主动连 HUD（扫描 /24，不写死 IP）
- 车机只能收文件到 `/sdcard/Download/` 后手动安装，**禁 `#INSTALLB64`**

## CastSink 版本
| 版本 | 内容 | 结果 |
|---|---|---|
| v1 (M1) | ClusterCastActivity 落屏探头 + 日志 | 验证 display 2 可拉起 |
| v2 | 加 HMI 探活/屏幕枚举/推测试图按钮 | 明确 DisplayManager 只见 0 |
| v3 | JPEG-over-TCP(27184) 渲染 | 帧可达 |
| v4 | EnvServer(27185) 环境导出 | 拿到 display 0/2 详细参数 |
| v5 | 透明窗口 + PNG alpha 合成 | alpha 生效，底为黑 |
| v6 | 上报 clusterView 尺寸 | 尺寸正常（盒子确认） |
| v7 | **双圆表本地绘制**（速度/转速） | 本地 30fps 渲染 |
| v8 | UDP 27186 监听 HUD 广播 | 未收到（AP 过滤广播） |
| v9 | TCP 27188 客户端 + /24 扫描 | 扫错网段（先命中 vt101=<LAN_IP>） |
| v10 | **优先 wlan*/swlan* 网段**再扫 | 车机成功连上 HUD |
| v11 | 去缓动 + 60fps | 跳变明显，体验差 |
| **v12** | 恢复 0.22 缓动@30fps；打开即自动 display 2 拉起；1.6s 后主屏自动回桌面；独立任务 | 定版 |

构建：`cd cast-sink && bash build.sh`（Kotlin，产物 `dist/CastSink.apk`）

## HUD 侧（hud-apk）
- `SpeedBroadcaster`：UDP 27186 广播（IP 写死 + 广播，保留作兜底）
- `SpeedServer`：TCP **27188** 推流，队列 + 单写线程；每行 `{"speed","rpm","ts"}`
- 数据源：`LocalLogcatReader` 用 `logcat -T <ts> -s AIOSLOG:*` **流式**读取（非轮询）
- 部署：`hud-apk/build.sh` → scp 到 Pi → `adb connect <HUD_IP>:5555` → `pm install -r` → force-stop/start
- HUD IP 漂移：实测 `.119 → .120`；重装前必须重扫

## 网络
| 主机 | IP | 备注 |
|---|---|---|
| 车机 wlan0 | <LAN_IP> | 入站全挡；Tailscale <TS_IP> 可用 |
| HUD | <LAN_IP> | ADB 5555 |
| Pi Zero 2W | <LAN_IP>（Tailscale <TS_IP>） | OBD 代理 :8080 |

- AP 过滤客户端广播（Pi tcpdump 12s 零包）
- 车机 → HUD：ping 7.4ms，TCP 可达；HUD/Pi → 车机：不可达

## 工具
- `tools/car-push-single.sh`：≤90KB b64 单行 printf（argv 上限 ~128KB）
- `tools/car-push-chunked.sh`：32KB **幂等**分块（独立 part 文件 + cat 拼接），重试不重复
- `tools/hud_speed_broadcast.py`：Pi 侧备用 UDP 广播器（当前未启用）

## 待办
1. 真实车速/转速改走 `CarProperty`（当前来自 HUD OBD）
2. DIY 仪表 UI（配色/字号/布局/告警）
3. HUD 发现策略优化（降低扫描频率、记住上次 IP）
4. 验证 v12 任务栈（自动回桌面、仪表常驻）

## 2026-10-20 v13 → v14（Bauhaus 重做）
- v13：双表盘重制（碳纤纹理 + 能量环 + 车身 + 数据条）→ 盒子评「花里胡哨过了」，弃用。
- v14：**Bauhaus 仪表**——无表盘、无渐变、无发光；96px 边距 / 32px 沟槽严格网格、超大字、红黄蓝三原色块。
- 数据项：档位推算（D1-D8/P）、PACK 总电量、2000E / 1000mini 分体电量、2000E 输入功率、AC 输出状态、车内 CO2、发动机实时功率（质量+风阻+滚阻+加速项估算）、续航/水温/机油/小计/总里程。
- 数据源：公网 HA `https://nas.<PRIVATE_DOMAIN>:8443/api/template`（与 HUD 同源，单次模板查询取 10 个值；token 复用 HudService 常量）。
- 构建：`cast-sink/build.sh` → 1.6MB APK（字体子集 + 无贴图）。
- 部署：ShellBridge 只能写 `/sdcard/Download/`，安装仍需手动点（uid=untrusted_app，无 INSTALL_PACKAGES）。

## v15（2026-10-21 00:0x）
- 实拍确认：仪表屏底部有原生状态条（含车速/挡位/温度），方向盘遮挡左右两侧。
- v15：**彻底去掉车速**；四栏 ENGINE / GEARBOX / BATTERY / CABIN；左右各留 12% 安全区；内容止于 y≈1070，避开原生底条。
- 字号加大（栏头 34、行标 26、数值 44），CO2 >1000 变红。
- APK `/sdcard/Download/CastSink_v15.apk` md5 `265b343ccbd7d1df4ae8aa272c3680a8`（已校验）。

## v16（2026-10-21 00:1x）
- 实拍二次校准：顶部两角被方向盘切掉、中下被原生整车图层覆盖。
- 改为**中置发动机**：中央 1240-2664 放 RPM/换挡灯/POWER/水温机油；左侧变速箱；右侧电池+座舱。
- 主次分级：L1 转速/挡位；L2 功率/PACK/CO2；L3 水温/机油/分体电量/AC/里程。
- APK `/sdcard/Download/CastSink_v16.apk` md5 `04e07806671d1db6eef4bcd02f59a9e4`（已校验）。

## v17-v19（2026-10-21 00:2x）
- 顶部赛车式 56 段转速条（含红区），中置 ENGINE，两侧数据。
- 动态背景：三层深色光晕缓慢漂移（蓝/琥珀/青），不干扰读数。
- AC/功率移到 PACK 右侧；全部数据压缩到 y<1000，避开原生底条。
- **去掉所有演示值**：无数据统一 “—”，OBD 显示 NO LINK；NetState 默认 -1。
- 自动启动：BootReceiver + AutoStartService（监听 display 2 ON，前台服务守护）。
- APK v19 `/sdcard/Download/CastSink_v19.apk` md5 `2d5e6f23df248dbf4f0f87b256a76298`。
