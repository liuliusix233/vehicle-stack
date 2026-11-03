# HUD 表盘 v6 — SPORT 运动版（设计稿 + 可运行原型）

> 2026-09-21 · 目标：在不改动现有 Theme A / Theme B 的前提下，新增一套运动风格表盘。
> 原型：`hud-apk/hud-design-refs/proto/sport.html`（可交互 demo）｜评审图：`proto/review/`

## 0. 硬约束（决定了所有设计参数）

| 约束 | 来源 | 设计后果 |
|:--|:--|:--|
| 480 × 240 px、2:1、纯黑底 | C2-Elite 面板 | 横向三段式布局；字号以 px 写死，不用 vw 糊 |
| HUD 合成器反射成像 | 光学路径 | **细线会消失**：禁 1px 刻度/描边，笔画 ≥2px，信息元素用实心块 |
| WebView on Android 8.1 | `MainActivity.kt` | 无 GPU 加速、无触摸；交互只有遥控器按键 |
| **禁止常驻 rAF** | 2026-08-06 实测教训（WebView 102% CPU 卡死） | 所有动画走 CSS transition/animation，且**按状态 class 开关**；数据 ~1–2Hz 到达 |
| 字体只有 Odibee Sans（单字重） | `assets/OdibeeSans-Regular.ttf` | 数字用 Odibee（大字号自带冲击力）；标签用 system-ui bold 撑粗 |
| 数据契约固定 | `updateData(d)` | 复用现有字段：speed / rpm / gear / fuelLevel / power / voltage / odo / heading / srvKm / engHrs / chargePower / piOn / nanoOn |

## 1. 参考（先定参考，再定方案）

**布局/信息架构 — GT3 DDU（Bosch DDU / Porsche 911 GT3 Cup / LMGT3 仪表）**
- 转速是主角：中央大弧度 + 分段式（segmented）填充；档位数字放弧内正中，是 GT3 的视觉签名
- 换挡灯带（shift light array）横贯屏顶，分段点亮，绿→琥珀→红三分区，到换挡点整条频闪
- 速度是次主角：大号纯数字 + 小号单位标签，不放表盘（数字比指针更快读）
- 次要信息（油量/电压/水温）贴边缘一字排开，不占中央视野
- 高对比、极少文字、单色系 + 一个警示色
- 参考：`newsroom.porsche.com` 911 GT3 座舱、`alphatecracing.com` GT3 dash、`lmu-dashboards.com` LMGT3 Porsche、GT7 HUD 设计讨论（gtplanet）

**动画/微交互参考**
- RPM 分区渐进填充：绿（低）→ 琥珀（动力带）→ 红（红线前），分段依次点亮（race-technology DASH2 换挡灯配置文档）
- 红线频闪：高频 strobe 抢注意力，短暂、不可误读（red/amber 全亮）
- 换挡确认：100–200ms 的档位数字闪白/放大/变色（瞬时正反馈）
- 启动自检：弧线 0→满→0 扫过一遍（"我活着"的仪式感，也是自检）
- 低量警示：低频脉冲（1.2s ease-in-out），不用闪烁（闪烁留给红线）
- 原则：所有动效必须"表达状态变化"，纯装饰一律砍掉（grandprixstudio / Google Cars 视觉原则）

**现有线上表盘的问题（对照 `hud_cur.png` / `hud115_ThemeB_确认.png`）**
1. 1px 刻度线 + 1.6–2.5vw（≈8–12px）小字 → 合成器上基本不可读
2. 速度 22vw 看似大，但周围塞满 12+ 个字段，没有视觉层级
3. Theme B 的 `ENGINE RPM` 条只有 8px 厚，且无分区，看不出"还能拉多少"
4. 状态点/标签用 1.6vw（≈8px）→ 等于没有

## 1.5 字体（v2 重构，2026-09-21 深夜）

Odibee Sans 只有一个字重且笔画偏轻，小字在合成器上直接糊掉 → 换成有粗字重、方切面、高 x-height 的工程/仪表类字体。

**候选样张**：`proto/specimen.html` → `review/10_fonts_1x.png`（12 款，1:1 真机尺寸）；**版式内对比**：`review/23_font_variants.png`

| 字体 | 许可 | 文件 | 评价 |
|:--|:--|:--|:--|
| **Rajdhani Bold** ⭐推荐 | OFL | 400KB | 方切角、笔画厚、科技感最正；数字有辨识度（1 带旗、2 平斜） |
| Saira Condensed Bold | OFL | 96KB | 压缩体，x-height 最大、数字最规整 → **纯可读性最佳**，体积也最小 |
| Chakra Petch Bold | OFL | 78KB | 切角科技，仪表味足；略「游戏感」 |
| Barlow Semi Condensed Bold | OFL | 111KB | 中性极简，最干净但不那么「科技」 |
| DSEG7 Classic | OFL | 269KB | 七段数码管；纯仪表 DNA，但字母不全 → 只适合数字 |
| ~~Michroma / Share Tech Mono / Electrolize~~ | | | 字面过宽或笔画过细，淘汰 |

- 全屏字体是参数化的：`sport2.html?f=rajdhani|saira|chakra|barlow` 一行切换
- 中文（罗盘方向）走系统 Noto Sans CJK Bold；如要全拉丁化可换 N/NW/NNW + Rajdhani
- APK 体积：可只保留数字+拉丁子集（fonttools subset 后 ~20KB），需时再做

## 1.6 排版重构（v2：解决「太挤」）

**病因**：v1 顶栏塞 4 组、中区右侧 3 行堆叠（4px 间隙）、底栏 5 组、RPM 数字压弧。

**处方**（`proto/sport2.html`，对比图 `review/22_spacing.png`）
1. 外层留白 14px（原 8px）→ 屏幕边缘有「呼吸」
2. 列/组间距 ≥14px，底栏组间距 20px（原 4–8px）
3. 分带更硬：顶栏只有「灯带 + 罗盘」两组；中区三组；底栏四组
4. 一屏字段数从 12 降到 9：**砍掉 SRV / EH**（保养类信息，留在 Theme A/B），电压移到底栏
5. 每条能量条独立成「标签行 + 14px 厚条」，两行之间留 26px
6. 转速弧 22 段 / 13px 粗，档位 46px 居弧心，转速数字放弧口下方（不再压弧）
7. 字号整体上调：速度 100→112px（S1）/ 104（S2），标签 ≥13px

**S2 垂直分带**：灯带 10–32 / 中区 40–172 / 转速条 174–194 / 底栏 200–240 —— 四条带互不挤压。

## 2. 可读性铁律（v6 全系列强制）

1. **字号下限 12px**（≈3.4mm@典型视距），主数字 ≥100px，次数字 ≥19px
2. **笔画下限 2px**：所有分隔线 2px、进度条 ≥11px 厚、弧线段 13px、灯带块 16px
3. **层级只有 3 档**：主角（速度/转速，白）＞ 次角（档位/罗盘，橙或白）＞ 辅助（标签，灰 #9db0bf）
4. **一屏不超过 4 种颜色**：白 + 橙 #FF5722（警示/主色）+ 蓝 #3399ff（电）+ 绿 #4CAF50（正常/低转速区）
5. 每个数字必须有单位，单位标签紧贴数字、加粗、字距 0.05em+
6. 前导零抑制（`1` 而不是 `001`），避免合成器上大片无意义白色

## 3. 两套方案

### S1 ·「GT3 DDU」（推荐）
```
┌───────────────────────────────────────────────────────────┐
│ ▮▮▮▮▮▮▮▮▮▮▮ 换挡灯带 15 段 (340px)            NW 312°  │ 32px
│                                                            │
│  KM/H          ╭── 转速弧 24 段 / 270° ──╮      FUEL  44% │
│                │         4              │      ▬▬▬▬▬▬     │
│   1 2 8        │      4700 RPM          │      BAT  100%  │
│  (100px)       ╰────────────────────────╯      ▬▬▬▬▬▬▬▬   │
│                                                VOLT 12.4V │
├───────────────────────────────────────────────────────────┤
│ ODO 38813.6 KM  SRV 323  EH 10.8h   ●PI ●NANO 9W   22:16 │ 34px
└───────────────────────────────────────────────────────────┘
```
- 坐标：灯带 x8–348；罗盘右对齐 x356–472；速度块 x8–178（数字 100px，底对齐 y206）；转速弧圆心 (261,118) 半径 66 笔画 13，270°（135°→405°）；档位 50px 居弧心；转速数字 26px 弧内下方；右侧 126px 三行能量条
- 特点：档位在转速弧正中（GT3 签名），速度仍是屏上最大的字

### S2 ·「速度优先」
- 速度 104px 居中（x96–316），档位 62px 左侧 + 电压，右上转速数字 44px，**满幅转速条 24 段 18px 厚**（y172），底部双能量条 + 状态点 + 时钟
- 适合"只想瞟一眼时速"的驾驶习惯；转速退成一条粗条

### 共同点（风格一致性）
- 同一套色板/字号体系/灯带与分段逻辑；两套都只用现有数据字段，无新增协议

## 4. 动态效果清单（全部 CSS 驱动，无 rAF）

| # | 效果 | 触发 | 实现 | 成本 |
|:--|:--|:--|:--|:--|
| 1 | 启动自检：弧线 0→7000→0 | 页面加载一次 | JS 顺序改 stroke，class 控制 | 一次性 |
| 2 | RPM 分段渐进填充 + 三分区变色 | rpm 更新 | 每段 stroke/class 切换，transition .09s | 每帧 O(24) DOM 写 |
| 3 | 换挡提示频闪（≥92% ≈ 6440rpm） | rpm ≥ 0.92×7000 | `.shift.hot` → 灯带 90ms strobe + 尾段转白 | 仅该状态 |
| 4 | 换挡确认闪光 | 档位数字变化 | `.gear.flash` 160ms 放大+橙 | 一次性 |
| 5 | 速度数字滚动（逐位翻滚） | 速度变化 | 每位 12 格数字带 + `translateY` transition .28s，9→0 走绕行格再瞬回 | 每次数据更新 |
| 6 | 罗盘方向（NW）+ 角度 | heading | 文本直更（大字号，不做绸带以保可读性） | 极低 |
| 7 | 低油量脉冲 | fuel < 15% | `.pulse` → 1.2s 透明度脉冲 + 转红 | 仅该状态 |
| 8 | 状态点呼吸（在线） | piOn/nanoOn | 常亮 + 光晕（不做闪烁，避免与红线混淆） | 静态 |
| 9 | 数据断链降级 | >3s 无更新 | 数值转灰 `--`，布局不跳（保留锚点） | 待接入 |

**性能红线**：任何 animation 只在对应状态 class 存在时才跑；`.no-anim` 用于截图/降级；单次数据更新只做 O(段数) 次 DOM 写。

## 5. 原型文件与复现

```
hud-apk/hud-design-refs/proto/
├── sport.html        # 自包含原型：?v=s1|s2 选版本，?frame=0..23 定格（无动画，用于截图），?fuel=1 强制低油量
├── cdp_shot.py       # 无头 Chromium + CDP 精确 480×240 渲染（Emulation.setDeviceMetricsOverride）
├── render_all.sh     # 生成 job 列表 → systemd-run 拉起 cdp_shot.py → 出全部图
├── compose.py        # PIL 合成评审图 + 24 帧 GIF
└── review/           # 01_s1_hero / 01_s2_hero / 02_states / 03_compare / 04_sport_demo.gif
```

**渲染坑（重要，已解决）**
- exec 环境的 `RLIMIT_AS` 硬上限 2GB → Chromium 直接 `Trace/breakpoint trap`（core dumped），即使 `ulimit -v` 也提不上去（hard limit）
- 解法：`systemd-run --user --unit=... --collect --wait --property=Type=oneshot python3 cdp_shot.py jobs.json`（走用户 systemd manager，脱离 exec 的 rlimit）
- Chromium 在 `--headless=new` 下 **`--window-size` 不控制布局视口**（实测恒为 500×153）→ 必须用 CDP `Emulation.setDeviceMetricsOverride` 指定 480×240（`deviceScaleFactor=2` 出 960×480 细节图）
- SER 上的 firefox snap 打不出截图（`--screenshot` 无输出），已弃用该路径

## 6. 接入计划（不动 A/B，待你点头）

1. `sport.html` 作为新 asset 丢进 `app/src/main/assets/`（新增文件，零风险）
2. `dashboard.html` 只加 3 行：`THEMES=['a','b','sport']` 三分支 + 加载时按主题切文件（或在 `applyTheme` 里切换 body 的 data 属性 + 用同一份 DOM）
3. 遥控器左右键循环 a→b→sport；HUD 上先跑一周看可读性，满意再考虑是否把 sport 设为默认
4. 若要新增字段（如油耗瞬时值、转向角），另开一轮改 JSON 契约——本轮不涉及

> 备注：S1/S2 均未改动 `dashboard.html`、Kotlin、Pi 代理任何一行；原型全部是新增文件。
