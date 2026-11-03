# HUD 仪表簇研究 — 2026-10-17（C/D 重做依据）

为 Theme C（ENERGY）与 Theme D（NIGHT）重做而做的真实仪表设计调研。
结论直接映射到 C/D 的信息架构；实现见 `app/src/main/assets/dashboard.html`（备份 `.bak_20261017_power`）。

## 一、来源清单

| 类别 | 来源 | URL |
|:--|:--|:--|
| 性能车 | Porsche 992.2 GT3 全数字仪表（Carscoops） | https://www.carscoops.com/2023/05/porsches-facelifted-911-gt3-shows-off-fully-digital-gauge-cluster/ |
| 性能车 | 992.2 GT3 Touring 数字仪表细节（Elferspot） | https://www.elferspot.com/en/magazine/porsche-992-2-gt3-touring-all-you-need-to-know-about-the-992-gt3-facelift-model/ |
| 性能车 | GT3 换挡灯带使用讨论（Rennlist） | https://rennlist.com/forums/992-gt3-and-gt2rs-forum/1392737-gt3-shift-assist-lights-daow-award.html |
| 新能源 | Tesla Model 3 仪表 UI 组件库（Figma） | https://www.figma.com/community/file/1382192547846546595/tesla-dashboard-ui-component-library |
| 新能源 | S3XY Dash 第三方仪表（Enhance） | https://www.enhauto.com/blogs/all/the-world-s-most-advanced-tesla-dashboard |
| 新能源 | Rivian Energy app 2025.18 改版公告 | https://rivian.com/stories/software-update-2025-18 |
| 新能源 | Rivian 驾驶屏能耗视图（论坛） | https://www.rivianforums.com/forum/threads/energy-consumption-display.3042/ |
| 新能源 | Lucid Air 座舱/仪表评测 | https://autoedgeview.com/ev-reviews/lucid-air-dash-range-tech-review |
| 新能源 | Prius 能量监视器官方手册（Toyota） | https://www.toyota.com/owners/warranty-owners-manuals/digital/article/prius/2026/om47f65u/ch02se010404/ |
| 双电瓶 | VictronConnect 产品页（Victron Energy） | https://www.victronenergy.com/panel-systems-remote-monitoring/victronconnect |
| 双电瓶 | VictronConnect 完整使用指南 | https://offgrid4less.com/uk/guides/victronconnect-app-complete-user-guide |
| 双电瓶 | REDARC RedVision Essentials Display | https://www.redarcelectronics.com/us/redvision-essentials-display |
| 航空 | Head-Up Display Design Guide（symbology 设计指南） | https://www.scribd.com/document/331931171/a192973-Hud-Symblogy |
| 航空 | Boeing VGS 商用 HUD 开发与认证论文（SETP） | https://www.hoh-aero.com/HUD/SETP_HUD_Paper.pdf |

## 二、分类结论

### 1. 性能车（Porsche GT3 / GT3 DDU / BMW M）
- **分段化（segmented）是性能仪表的通用语言**：GT3 DDU 的转速弧、BMW M 的中央转速条都由粗分段块构成，绿→琥珀→红三分区，接近红线整带频闪——分段比连续渐变条更快被周边视觉读出（Rennlist 对换挡灯的抱怨全部围绕"看不见"而非"看不懂"）。
- **数字 > 指针**：992.2 用纯数字速度取代指针表；速度、档位用最大字号，转速弧只是背景框架。
- **次要信息（油量/电压/水温）贴边缘、缩小、降亮**，绝不与主读数抢面积。
- 对 C 的映射：能量条用**粗分段实心条**（repeating-gradient 切段，段高 ≥1vh），不用 1px 细线；告警用**闪烁 pill**，不靠颜色微变。

### 2. 新能源（Tesla / Rivian / Lucid / Prius）
- **Tesla**：power meter 是一根横表——左半回收（蓝）、右半输出（白/红），指针式读数；电池 % 永远配实心填充条，且与续航 km 成对出现（% 回答"还剩多少电"，km 回答"还能跑多远"）。
- **Rivian**：Energy app 独立成页，电池图形用蓝色填充表达可用能量，冷天用浅蓝标"暂时不可用"——**同一根条区分可用/不可用**；能耗视图（kW/km）是驾驶屏一级页面，不是隐藏菜单。
- **Prius 能量监视器**：整车只有一块"能量流图"（发动机→电池→轮），停车/充电/制动回收各有独立动画态——**混动车的仪表身份是能量流，不是速度表**；官方手册把"驱动状态/混动系统状态/回收状态"列为一级信息。
- 对 C 的映射：C 的右半屏 = **电力系统三行堆叠（12V 总线 / 户外电源 / 油箱）**，每行 = 标签 + 大数值 + 粗分段条 + 右侧派生值（净W / 续航km）；顶部状态 pill 直接回答"现在电从哪来到哪去"（CHARGING / ON BATTERY / DIRECT SUPPLY / STANDBY），停车充电时整脸照常可读。

### 3. 越野/房车双电瓶（Victron / REDARC）
- **电压 + 电流 + SoC 是双电瓶系统的三件套**，任何正经监控器（VictronConnect、RedVision）三者同屏，缺一不可。
- **REDARC 的卖点是"a fuel gauge for your battery"**：一块屏只回答"电瓶还剩多少"，用大字号 SoC + 条；说明双电瓶用户对"电量%"的读取优先级高于一切细节。
- **Victron 用文字读数（电压/充电电流/充电阶段）+ 历史曲线**，静态页也保持全字段可读——停车时盯的正是这些。
- 电压告警阈值共识：静置 <11.8–12.0V 即亏电，行车 13.5–14.6V 为正常充电区。对 C/D：电压条量程 10.5–15.0V，<11.8V 红 + LOW VOLTAGE 告警 pill；DJI 电源 <10% 红 + LOW STATION。

### 4. 航空 HUD（夜航）
- **单色显示保暗适应**：军用/民航 HUD 用单一磷光色（绿或琥珀），绝不多色——多色在暗光下各自失焦；红色保留给告警，因为**红光对暗适应破坏最小**（夜视设备普遍用红）。
- **亮度即层级**：主飞行参数（空速/姿态）最亮最大，次要数据（高度带/航向带）用小字号+低亮度；亮度靠不透明度分级，不靠换颜色。
- **信息位置固定**：空速永远在左侧固定位置，飞行员用周边视觉扫读，不需要"找"。
- 对 D 的映射：D 全脸只用 #FF9800 琥珀一个色相；告警用 #FF3B30 红（红光保暗适应）；电力轨用 45–55% 不透明度的极低亮度琥珀，字号最小档；发光面积压到最小（速度数字 + 一条轨 + 两个角标）。

## 三、三层信息架构提炼（通用）

| 层级 | 回答的问题 | 停留 | 通用做法 |
|:--|:--|:--|:--|
| 第一眼 | 我多快？ | <0.3s | 全屏最大字号、位置固定、最亮 |
| 瞟一眼 | 这车的能量状态对不对？档位？还能跑多远？ | 0.3–1s | 粗分段条 + 大数值 + 状态 pill（GT3 分段带 / Tesla 电池条 / REDARC SoC） |
| 查阅 | 电压、净功率、油耗、海拔、坡度、里程、时间 | 停车/安全时 | 小字、低对比、贴边缘（Victron 文字读数式） |

## 四、对 C/D 的具体映射（本次实现）

**C — ENERGY（行车 + 能源管理）**：速度保持第一眼（左 42%，24vw）；脸的**身份 = 电力系统**——右半屏三行粗分段条（12V 总线 / DJI 户外电源 / 油箱），每行带数值与派生值（净W、续航km）；顶部状态 pill（CHARGING/ON BATTERY/DIRECT SUPPLY/STANDBY + 净W）+ PI/NANO 连接点 + 时钟；LOW VOLTAGE / LOW STATION 告警 pill 闪烁。罗盘从 C 移除（v7 的 C 复用 A 的罗盘带是"像 A"的根因），航向降为页脚文字。停车/充电态整脸可读（速度 0/N 档 + CHARGING pill + 功率数字）。

**D — NIGHT（夜航低眩光）**：琥珀单色（#FF9800）+ 红光告警（#FF3B30）；速度 28vw 居中（航空式固定位）；档位描边 chip；**底部一条极低亮度电力轨**：电压 + 户外电源% + 净W + 分段 mini 条（55% 不透明度，2px 段）；顶部两角只留航向 + 时钟（#6b4700/#8a5a00）。砍掉：油量条、续航、ODO、油耗、坡度——夜间需要时切回 A/B/C。

**两脸 IA 差异**：C = 左速度 + 右能量堆栈 + 顶部状态带（横读）；D = 中央速度 + 底部单轨（中心放射）。C 是"能源管理台"，D 是"夜视最小集"，都不是 A 的裁剪。
