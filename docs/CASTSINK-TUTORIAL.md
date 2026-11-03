# 自己写一个车机投屏接收端：CastSink 原理拆解

> 面向：想在 Android 车机上做第二屏显示的开发者
> 前提：普通签名的 APK + 一条可用的调试/命令通道，**不需要 root，不需要平台签名**

---

## 0. 为什么自己做

车机自带的投屏（CarPlay / Android Auto / 厂商互联）都走认证链路，普通开发者既装不进去也改不了。但"把画面显示到车机屏幕上"这件事，本质上只需要三样东西：

1. 一条能进数据的通道
2. 一个能解码 H.264 的解码器
3. 一块能画上去的 Surface

Android 全都有。难的不是技术，是**绕开 OEM 对多屏和系统权限的收紧**。

---

## 1. 总体架构

```
发送端(手机/PC/树莓派)
      │  TCP :27183
      │  4 字节大端长度 + H.264 Annex-B 访问单元
      ▼
┌─────────────────────────────────────────┐
│ 车机 App                                  │
│                                          │
│  TcpReceiver ──帧──► MediaCodec ──► Surface │
│       │                  │                │
│       │                  ├─► 主屏 SurfaceView（预览/调试）│
│       │                  └─► 副屏 Activity（仪表/后排）  │
│       │                                   │
│  DisplayHelper：枚举副屏 + 拉起策略         │
└─────────────────────────────────────────┘
```

一次只做一件事：**收帧 → 解码 → 上屏**。不做协议协商、不做音频、不做回控，先把画面跑通。

---

## 2. 传输层：为什么自己定协议

参考 WFD（Wi-Fi Display）的分帧思路，但把协议栈砍到最小：

- **4 字节大端长度前缀 + 一个访问单元（AU）**
- 载荷是 Annex-B 格式，每帧以 `00 00 00 01` 起始码开头
- 一个 TCP 连接，断线自动重连

不用 RTP/RTSP 的原因很实际：  
WFD 需要系统媒体通道和 HDCP 相关的协商，普通 App 拿不到；自定协议两百行就能跑通，而且可以跑在 WiFi、热点、USB 网络、甚至 4G 上，对链路没有假设。

**两个必做细节：**

- **背压丢帧**：解码跟不上时丢非 IDR 帧，绝不阻塞 socket。否则延迟会一路累积到几秒。
- **重连要清解码器**：新连接的第一帧通常是 IDR，但旧解码器里还留着上一路的状态，必须先重建。

---

## 3. 解码层：MediaCodec 的四个坑

用 `video/avc`，异步模式，`configure(surface)`：

1. **直接配 Surface，不要取 ByteBuffer 再自己画。**  
   output buffer 直接由硬件合成器上屏，零拷贝，功耗和延迟差一个数量级。

2. **SPS/PPS 门控。**  
   第一帧 IDR 之前必须已经喂过 SPS/PPS，否则尺寸、颜色、裁剪全错。做法是缓存最近的 SPS/PPS，遇到 IDR 前先补喂。

3. **坏帧只能重建，不能 reset。**  
   解码器报错后 `reset()` 在部分车机硬解上会卡死，正确做法是 `stop() + release()` 重新 `createDecoderByType`。

4. **PTS 要单调。**  
   车机硬解对时间戳回退很敏感，回退会直接丢帧或黑屏。按到达顺序自增即可，别用真实时钟。

---

## 4. 多屏：整个项目最有意思的部分

车机的"第二块屏"通常不是外接显示器，而是一块独立 Display，由系统服务管理。

**枚举：**

```kotlin
val dm = getSystemService(DisplayManager::class.java)
val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
// 打印 displayId / name / 分辨率 / 刷新率
```

**拉起（标准做法，经常失败）：**

```kotlin
val options = ActivityOptions.makeBasic()
    .setLaunchDisplayId(targetDisplayId)
startActivity(intent, options.toBundle())
```

普通签名 App 调这个，在多数量产车机上会直接抛 `SecurityException`——OEM 把多屏启动收进了系统权限。

**回退（真正能用的做法）：**

走设备上的命令通道（adb / 厂商调试口 / 自己搭的 shell 桥），执行：

```
am start --display <id> -n <包名>/<Activity>
```

这条路径以 shell 身份运行，不受普通 App 的多屏限制。

配套要求：

- 目标 Activity 必须 `exported="true"`
- `launchMode="singleTask"`，避免被反复拉起
- `configChanges` 覆盖 `screenSize|smallestScreenSize|density|orientation|screenLayout`，否则分辨率一变就重建，画面闪一下
- 在 `onResume` 里打印 `windowManager.defaultDisplay.displayId`，**确认真的上了副屏**，而不是只在自己脑子里上了

---

## 5. 权限：普通 App 到底能做到哪一步

| 能力 | 需要的权限级别 | 普通安装能否拿到 |
|---|---|---|
| 网络收发 / 前台服务 | normal | ✅ |
| 解码 + 显示到自己的 Surface | 无 | ✅ |
| 枚举副屏 | 无 | ✅ |
| 用 ActivityOptions 拉起副屏 | signature / OEM 自定义 | ❌（走 shell 回退） |
| 注入输入事件 | signature | ❌ |
| 读取车辆总线数据（车速/档位/电量） | 厂商 signature + privileged | ❌ |

**结论：投屏本身完全不需要系统权限。**  
需要系统权限的只有两类：**读车辆数据**、**注入输入**。

顺带一个常被误解的点：**回控（UIBC）不需要 INJECT_EVENTS**。  
输入事件是作为协议消息回传给源端处理的，不注入 sink 端系统输入管线，所以普通权限就能做。

---

## 6. 工程上的坑（按踩到的顺序）

1. **车机没有 Google 框架**，别用 Cast / MediaProjection 那一套，直接写 MediaCodec。
2. **硬解能力因芯片而异**，1080p60 不一定能跑，先测 720p30 保底。
3. **屏幕方向 ≠ 视频方向**，竖屏车机要自己做旋转矩阵，别指望 Activity 帮你转。
4. **开机自启 ≠ 能拉起副屏**，自启的 Service 没有 Activity 栈，得配合前台服务 + 延迟拉起。
5. **别在 UI 线程做 socket**，车机 CPU 调度比手机保守，主线程一卡就是掉帧。
6. **副屏 Activity 被系统回收是常态**，做好"被杀了就重新拉起"的守护逻辑，别假设它一直在。

---

## 7. 可以复用的部分

这套结构不只能投屏：

- 换成 **H.265 / AV1**，只改 MIME 和 SPS 处理
- 换成 **JPEG 序列**，就是一个低延迟的仪表壁纸通道
- 换成 **HUD 数据**（车速、转速、导航指令），就是一块自绘仪表
- 加一层 **本地 socket**，可以让外部进程往车机屏幕推任意内容

核心就一句话：**车机就是一台 Android 设备，只要你能进数据、能解码、能找到那块 Display，剩下的都是工程问题。**

---

## 免责

本文只讲原理与通用 Android API 的用法，不涉及任何厂商固件、签名密钥或越权方法。  
在你自己的设备上做实验，注意别影响行车安全相关的功能。
