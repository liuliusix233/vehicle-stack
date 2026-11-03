# ShellBridge

受限 Android 车机上的 **TCP shell 桥**：手机/PC 通过 Wi-Fi 执行白名单命令、推文件、播语音，替代不稳定的 adb over Wi-Fi。

## 构建

纯 Java，无 Gradle：`aapt` → `javac` → `d8` → `zipalign` → `apksigner`。

```bash
./build.sh          # 需要 tools/android-sdk（build-tools 34.0.0 + platforms/android-31）
# 车机开不了 ADB：拷贝 dist/ShellBridge.apk 到 U 盘 → 车机文件管理器安装
```

## 协议

TCP 7801，行协议：`#CMD args`。默认白名单见 `src/` 中 `CommandHandler`。

```bash
echo '#PING' | nc $CAR_HOST 7801        # -> PONG
```

## 坑

- 厂商可能杀后台：需加入电池白名单 + 前台服务。
- `am start` 可能被拦截，改用 `monkey -p <pkg> 1`。
- `build.sh` 里的签名口令是**调试自签名**，正式分发请换成自己的密钥。