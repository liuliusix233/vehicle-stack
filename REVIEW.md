# 审查指南（REVIEW）

> 给你自己过目用的。按顺序走，30 分钟能过完关键项。

## 0. 先跑三条命令

```bash
cd vehicle-stack
python3 tools/sanitize.py --root . --out /tmp/scan.json   # 期望 HIGH/MED/LOW 全 0
bash tools/preflight.sh                                    # 末行 PREFLIGHT OK
git log --oneline                                          # 两个提交
```

## 1. 最高优先级：先看有没有漏掉的隐私（10 分钟）

```bash
grep -rni -e 'liuliusix' -e 'geomax' -e '\<USER>' -e '盒子' -e '石蔓' -e '<你的手机号>' . --exclude-dir=.git
grep -rn 'CN_' . --exclude-dir=.git | head        # DJI token 前缀
grep -rnE '([0-9]{1,3}\.){3}[0-9]{1,3}' . --exclude-dir=.git | grep -v '192.0.2' | head -20
```

重点文件：

- 发布前跑一遍 `tools/sanitize.py`，确认 HIGH = 0
- `software/power/*.example.json`（应为占位符）
- `software/power/dji-power-ble/docs/PROTOCOL.md`（抓包字段可能含设备标识）
- `alternatives/c2-elite-hud/app/**/*.kt`（HA_TOKEN 已换占位符）

## 2. 其次：代码能不能真的跑起来（15 分钟）

| 组件 | 关键文件 | 看点 |
| --- | --- | --- |
| shellbridge | `build.sh`, `src/` | 路径是否全相对；白名单命令是否合理 |
| castsink | `build.sh`, `app/` | `ANDROID_TOOLS` 变量是否够用 |
| hud | `fb_dash.py`, `install.sh` | `install.sh` 里 `<TS_IP>` 占位是否要改成参数 |
| power | `dji_power_receiver.py` | `dji_cloud_config.json` 缺失时是否优雅降级到 BLE |
| relay | `relay.py`, `pi_tunnel.py` | token 读取路径 `/home/<USER>/...` 需不需要改 |
| headunit-vhal | `docs/VHAL-22.md`, `probe.sh` | 22 个属性表与实测值是否还有个人信息；ID 陷阱说明是否清楚 |

**注意**：仓库**不含** vendored JDK/Android SDK（原 600M+），所以 APK 不能开箱即构建。请确认你要不要补 `tools/fetch-toolchain.sh`，或者只写依赖说明。

## 3. 再看文档是否自洽

- `AGENTS.md` 的验收命令是否和你实际流程一致（端口 7801 / 8477 / 8080）
- `docs/ARCHITECTURE.md` 拓扑是否画错
- `README.md` 的兼容性声明（Android 12 / Pi 4-5 / fb0 16bpp）

## 4. 已知待办

- [ ] `software/sentinel/` 只有设计占位，源码待补
- [ ] LICENSE 的 `<COPYRIGHT HOLDER>` 待填
- [ ] `hardware/` 待你写

## 5. 我自己踩过的坑（已修，供你复核）

1. 自动脱敏曾把 `os.environ.get(...)`、`threading.local()` 吃成占位符 → 已改规则并全量重跑，`compileall` 通过。
2. `cast-sink/build.sh` 原写死 `/home/<USER>/...` → 已改为脚本相对路径。
3. 4 个 `duml/*.py` 在上游就是 `404: Not Found` 坏文件 → 已剔除并在 power README 说明。