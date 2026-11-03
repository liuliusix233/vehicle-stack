# 发布流程

## 版本号

`vMAJOR.MINOR.PATCH`：

- **MAJOR**：破坏性改动（协议、配置格式、目录结构）
- **MINOR**：新增组件或功能
- **PATCH**：修复、文档、脱敏

当前：`v0.1.0`（首个公开版本，协议未冻结）。

## 打一个 release（本地）

```bash
# 1) 确认干净
python3 tools/sanitize.py --root . --out /dev/null --fail-on HIGH --quiet
python3 -m compileall -q software

# 2) 版本号写进 CHANGELOG
#    把 [Unreleased] 改成 [x.y.z] — YYYY-MM-DD

# 3) 打标签
git tag -a v0.1.0 -m 'v0.1.0 — 首个公开版本'
git push origin main --tags
```

## Release 附什么

| 附件 | 为什么 |
| --- | --- |
| `CastSink.apk` | 别人不用装工具链就能先验证车机侧 |
| `ShellBridge.apk` | 同上 |
| `vehicle-stack-src.zip` | 源码快照（与 tag 一致） |

APK 用**调试签名**，附一句说明：「调试签名，安装前请卸载旧版本」。

## 发布前 checklist

- [ ] `sanitize.py` HIGH = 0
- [ ] `compileall` 通过
- [ ] `docs/HOW-WE-BUILT-IT.md` 与 README 定位一致（档案，不是安装手册）
- [ ] `docs/gallery/` 图片已确认无隐私
- [ ] LICENSE 版权人已填
- [ ] tag 与 CHANGELOG 版本一致