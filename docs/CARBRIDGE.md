# CarBridge v75 — 合并车机 App 架构与运维（2026-10-26）

## 结论
- 包名：`com.vox.castsink`，版本 **v75 (versionCode 75)**。
- 一个 APK = CastSink HUD + ShellBridge 命令服务 + 反向隧道 + 音频播放。
- 娱乐屏保持“什么都不显示”：`MainActivity` 透明、拉起 display 2 后 `goHome()`；仪表内容在 display 2。
- 仪表左侧选项卡已删除（v74 起），内容槽全屏。

## 源码与构建
- 工程：`/home/<USER>/.openclaw/workspace/jarvis/cast-sink`
- Kotlin：`app/src/main/java/com/vox/castsink/*.kt`（含 `RelayTunnel.kt`）
- 合并 Java：同目录 `ShellService.java` / `CarScanner.java` / `VehicleReceiver.java`（包名已改为 `com.vox.castsink`）
- 关键改动：
  - `ShellService` 端口 **8888**，通知 ID 2（避开 AutoStartService 的 1）。
  - 启动时调用 `RelayTunnel.INSTANCE.start()`；`BootReceiver` 同时拉起 AutoStartService + ShellService + 隧道。
  - 端口被旧 `com.vox.shellbridge` 占用时，自动向 127.0.0.1:8888 发 `killall com.vox.shellbridge` 完成接管。
  - `#PLAY <path> [--vol 0-100]` / `#STOPAUDIO`：用 `MediaPlayer` 走 AudioFlinger 播放；`tinyplay` 被 SELinux 拒绝（/dev/snd）。
  - `#CLUSTER START` 指向 `.ClusterCastActivity`；`#CLUSTER STOP` 发 `com.vox.castsink.FINISH_CLUSTER`。
- 构建：
  ```bash
  cd /home/<USER>/.openclaw/workspace/jarvis/cast-sink
  JAVA_OPTS="-Xmx512m -Xms128m -XX:CompressedClassSpaceSize=256m" bash build.sh
  # -> dist/CastSink.apk
  ```
- 工具链：`hud-apk/tools/kotlinc` + `hud-apk/tools/android-sdk`（android-27、build-tools 29.0.3）+ `shell-bridge/tools/jdk17/bin/{javac,jar}`。
- 构建坑：
  - kotlinc 必须同时把 3 个 Java 源文件放命令行做符号解析；javac 的 classpath 要含 `build/classes.jar`。
  - 系统无 `jar`，用 JDK17 的 `bin/jar`。
  - D8 可能跑 2–3 分钟；不要重复启动构建。

## 网络与隧道
- 直连（优先）：Tailscale `<TS_IP>:8888`。
- 公网中继（Tailscale 掉线时）：车机主动连
  `wss://nas.<PRIVATE_DOMAIN>:8731/car-tunnel?role=car&token=<CAR_TOKEN>`
- 家里 relay：`tools/car-relay/relay.py`，systemd 用户单元 `car-relay.service`。
  - 监听 127.0.0.1:19001（给 Caddy）+ 127.0.0.1:8899（本机操作端）。
  - Caddy `nas.<PRIVATE_DOMAIN>:8731` 增加 `/car-tunnel*` 和 `/car-health` 路由；8444 同样已加。8731 是已有的公网转发口，未新增路由器规则。
  - 令牌：`secrets/car-relay.env`（CAR_TOKEN/OP_TOKEN，600）。
  - 运营端：`tools/car-relay/op_client.py`（WSS）或 `nc 127.0.0.1 8899`（本地 TCP）。
- 中继关键修复：
  - 操作端 EOF 后**不能立刻**给车机发 CLOSE；必须等车机处理完回 CLOSE，否则长命令/分块被截断。
  - 中继启动后车机重连有 2–60s 退避；重启 relay 后耐心等，别连续重启。
  - 健康检查：`curl -s http://127.0.0.1:19001/car-health`。

## 铁律：8155 只能人工安装（2026-10-26 盒子明令）
- 车机 **无 ADB、无 root、无主动安装权限**：不允许 `pm install` / `adb install` / PackageInstaller 会话 / `#UPDATE` / `#INSTALLB64` 当成安装手段。
- **唯一路径**：APK 放进 `/sdcard/Download/`，由盒子在文件管理器里手动点装。
- 交付前自检：文件管理器能看到**大小**且点击有反应 = 可装；只显示图标、无大小 = MediaStore 没索引，等于没交付。
- 扫描命令 `#CALL content://media scan_file <path>` 必须返回 `content://media/external_primary/file/<id>`；返回 `STREAM = null` 就是失败，别当成功。

## 文件推送（Download 手动安装）
- 首选：直连 8888；Tailscale 不通走 127.0.0.1:8899。
- 2MB APK 的单行 base64 约 2.67MB，超过 Android sh 单参数 128KB 上限；必须分块：
  - 每块 60KB base64，`printf %s '<chunk>' >> /sdcard/Download/.tmp.b64`。
  - 最后 `base64 -d ... > /sdcard/Download/<name>.apk && md5sum`。
- 更稳的替代：`#INSTALLB64` 只用来把 APK 传到 app 内部（`/data/user/10/com.vox.castsink/files/update.apk`），再
  `cp ... /sdcard/Download/CarBridge_vXX.apk`；**不要**指望它完成安装。
- **关键坑：直接 shell 写入 Download 的文件不在 MediaStore，文件管理器会显示无大小、点不开。**
  推送后必须：
  `#CALL content://media scan_file /sdcard/Download/<name>.apk`
  返回 `content://media/external_primary/file/<id>` 才通知用户安装。
- 校验：车机 `md5sum` 必须等于本地；不一致重推。

## 音频测试
- `tinyplay`：`cannot open /dev/snd/pcmC0D0p`（SELinux，app uid 无 ALSA）。
- `cmd notification post`：permission denied。
- `#PLAY /sdcard/Download/beep.wav --vol 60` -> `PLAYING ... dur=400ms vol=15/25`，实车可听到 880Hz。
- 音量按 STREAM_MUSIC 设置；`#STOPAUDIO` 停止。

## 版本同步
- AndroidManifest：versionCode **75** / versionName **v75**。
- 本文档、MEMORY.md、两个 skill 均记 v75。
- 旧应用 `com.vox.shellbridge` 仍可能安装；新服务会抢 8888 并 kill 旧进程，建议手动卸载旧包。

## 快速自检
```bash
# 直连
printf '#HELP\n' | timeout 10 nc <TS_IP> 8888 | head -3
# 中继
printf '#PERMS\n' | timeout 20 nc 127.0.0.1 8899 | head -5
# 播放
printf '#PLAY /sdcard/Download/beep.wav --vol 80\n' | timeout 20 nc 127.0.0.1 8899
```
