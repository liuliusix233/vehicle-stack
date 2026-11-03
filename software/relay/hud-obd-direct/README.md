# HUD 直连 OBD 抓取（方案 A：绕开车萝卜 AIOBD## 通道）

## 背景（2026-08-16）
- 车萝卜 C2-Elite HUD 有硬件直连 OBD：`/dev/ttyMT0`（MTK UART，权限 777，普通 app 可读写）
- aicore (com.ileja.aicore, /system/app/HudcoreAicore/) 通过 SerialPortImpl 读该串口
- 私有协议帧示例（aicore 日志）：
  `SerialPortImpl.sendDataPackage: 发给OBD的数据<<<<<<<<7E8004000600010000000101000300807E`
  帧结构：`7E` 开头（HDLC 标志？）... `7E` 结尾
- aicore 解析后输出 AIOBD## logcat → 我们的 HudService 显示
- 目标：直接从 ttyMT0 读 OBD 数据，绕开车萝卜整个软件栈

## 待确认
- [ ] 波特率（试过 9600/38400/57600/115200 熄火时无数据，可能熄火 OBD 断电）
- [ ] 私有帧协议格式（需逆向 HudcoreAicore.apk 或抓取运行中数据）
- [ ] 车启动时 ttyMT0 数据流（下次开车现场抓）

## 攻击路径
1. 拉取 /system/app/HudcoreAicore/HudcoreAicore.apk → jadx/apktool 反编译
2. 找 SerialPortImpl / com.cpsdna.obdport / DinaObd 相关类，提取波特率+协议
3. 写 HUD 端串口服务（Android app 或 shell 脚本），直接解析
4. 输出格式对齐现有 HudService 的 ObdData 广播

## 相关参考
- Pi 旧方案（已废弃，蓝牙 OBD）：archive/hud-pi-migration/obd_bridge.py — 也是读 AIOBD##，非直连
- HUD APK 源码：hud-apk/（HudService.kt 读 AIOBD## 的现有逻辑）
