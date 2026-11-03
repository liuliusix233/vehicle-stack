# CastSink

Android 投屏**接收端**：把手机/电脑画面接到车机屏幕（MediaProjection + 前台服务）。

## 构建

```bash
export ANDROID_TOOLS=/path/to/android-tools   # 含 kotlinc/、android-sdk/、debug.keystore
./build.sh
adb install -r dist/CastSink.apk
```

## 验收

车机桌面出现 CastSink 图标 → 手机「无线投屏」可发现设备 → 画面延迟 <300ms。

## 坑

- 需要 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限。
- 部分车机禁止自启动，需手动打开一次。