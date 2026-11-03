# CastSink 权限说明（M1）

## 1. 普通安装即可获得（normal 级）

- `android.permission.INTERNET` — TCP 27183 监听/收发
- `android.permission.ACCESS_NETWORK_STATE` — 网络状态查询
- `android.permission.FOREGROUND_SERVICE` — M2 前台服务保活预留（M1 已在 manifest 声明）

以上均为 normal 签名级别，普通 `adb install` 即自动授予，无需任何特殊操作。

## 2. 必须平台签名 / privapp 才能获得

- `android.permission.INTERNAL_SYSTEM_WINDOW` — 系统层窗口（signature|internal，OEM 仪表屏 Activity 拉起常要求）
- `android.permission.INJECT_EVENTS` — 输入事件注入（signature）
- `com.gm.vehicle.permission.*` — GM 车辆数据访问（signature|privileged，厂商自定义权限）

普通安装申请这些权限会被安装器拒绝（`Installation aborted` / 权限不授予）。需要平台签名（platform key）或作为 priv-app 预装到 `/system/priv-app` 并列入 `privapp-permissions` 白名单。

## 3. 绕过路径（不做平台签名）

- **仪表屏拉起**：ShellBridge 执行 `am start --display <id> -n com.vox.castsink/.ClusterCastActivity` — shell uid 可指定 `--display`，绕开 `ActivityOptions.setLaunchDisplayId` 在 OEM 上的 SecurityException（代码已内置：`ClusterDisplayHelper.launchCluster` 捕获异常并生成该命令，`buildAmStartCommand()` 可单独取用）
- **权限授予**：`pm grant` / `appops set` 给普通 App 授予部分 appop 类权限
- **车辆数据**：App 不持有 `com.gm.vehicle.permission.*`，由 ShellBridge 执行 `dumpsys car_service` 后经本地 socket 把结果喂给 App

## 4. UIBC 回控结论

借鉴 Carlink 设计：**UIBC（User Input Back Channel）回控不需要 INJECT_EVENTS**。
输入事件作为协议消息回传给源设备（手机侧消费），并不注入 sink 端系统输入管线，因此普通权限即可实现。M1 不实现 UIBC；M2+ 通过 socket 协议消息实现回控，无需申请 signature 权限。
