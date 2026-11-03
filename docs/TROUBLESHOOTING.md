# 排障

## 构建

### `build.sh` 报找不到 android.jar / kotlinc
没跑工具链脚本。执行 `bash tools/fetch-toolchain.sh`，或 `export ANDROID_SDK_ROOT=/path/to/sdk`。

### d8 静默失败、没有 dex
后台跑（`nohup ... &`）会被回收进程树。改前台运行；仓库的 `tools/build_castsink.sh` 已用 flock + 前台，照抄它。

### apksigner 报 keystore 密码错
`build.sh` 里的口令是**调试自签名**。换成自己的 keystore：`keytool -genkey -v -keystore my.jks ...`，再改脚本变量。

## 车机

### 车机开不了 ADB（本车就是）
装 App 只能：**U 盘（FAT32）拷 APK → 插车机 USB → 用自带文件管理器/本地安装打开 APK**。
没有 adb 时排障靠：App 自己的日志（`/sdcard/Android/data/<pkg>/files/`），用 ShellBridge 的 `#READ <path>` 拉出来。

### `adb connect` 通但 `install` 报 `INSTALL_FAILED_USER_RESTRICTED`
车机设置里关掉「USB 安装校验 / 应用验证」，或在弹窗里点允许。部分厂商要在开发者选项里单独开「USB 安装」。

### `am start` 被拒（Permission Denial）
厂商拦截。改用 `monkey -p <pkg> 1`，或让用户手动点图标。

### 7801 端口连不上
1. 车机和电脑是否同一网段；2. App 是否在前台/被电池策略杀掉；3. `netstat -tlnp | grep 7801` 看有没有监听。

### ⚠️ 7801 没有鉴权
当前实现**只靠局域网隔离**。不要把车机连到公共 Wi-Fi；如需暴露，自己在前面加反向代理 + token。

## HUD

### 画面花屏 / 错位
面板实际 **240×960**。改 `fb_dash.py` 顶部的宽高与 bpp（16bpp）。

### 中文变方块
缺 CJK 字体。`fonts/` 里已自带，确认 `fc-list | grep -i noto` 能找到，或改脚本里的字体路径。

### 写 /dev/fb0 权限拒绝
`sudo usermod -aG video $USER` 后重新登录，或用 root 跑服务。

## 电源

### `--once` 报 BLE 超时
1. `bluetoothctl` 能扫到设备吗；2. 用户是否在 `bluetooth` 组；3. 电源是否已被手机 App 占用（BLE 单连接）。


## 端到端

### `e2e.sh` 某项 FAIL
逐项拆：`ping` → `nc` → `curl`。多半是 `.env` 里的 IP 填错，或防火墙拦了 7801/8123。