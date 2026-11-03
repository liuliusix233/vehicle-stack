# HUD Dashboard v4 — 设计文档

## 屏幕规格
- 分辨率: 480 × 240 (C2-Elite HUD)
- 宽高比: 2:1
- 底色: #000 (纯黑)
- 字体: Odibee Sans (等宽风格数字)

## 布局 (CSS Grid 4列3行)

```
┌──────────────────────────────────────────────────┐
│ c1              c2    c3              c4         │
├──────────────────────────────────────────────────┤ r1
│  W  WNW  NW  NNW ↑N  NNE  NE  ENE  E           │ ← 指南针横条
├──────────────────────────────────────────────────┤ r2
│      ████████                                    │
│        80         ║█║   [N]                      │ ← 速度 + RPM条 + 档位
│     KM/H                                         │
├──────────────────────────────────────────────────┤ r3
│ ODO  │ BAT ████████████ 100% │                   │
│30645 │ FUEL ████████░░░  44% │             23:08 │ ← 里程 | 电量+油量 | 时钟
└──────────────────────────────────────────────────┘
```

### Row 1 — 指南针横条 (1/6 高度)
- 全幅横条，overflow:hidden，15vw 左右内边距遮罩
- 16 方向点 + 前后各 4 个重复项实现无缝滚动
- CSS transform: translateX() 动画 (0.15s ease)
- 当前方向居中高亮 (#FF5722, 4vw)，邻近 dim (#8899aa, 3.2vw)
- 16 方向: N, NNE, NE, ENE, E, ESE, SE, SSE, S, SSW, SW, WSW, W, WNW, NW, NNW

### Row 2 — 速度区 (2/6 高度)
- 速度数字: 22vw, #fff, 居中对齐
- KM/H 标签: 5vw, #445566, 右下对齐
- RPM 条: 2vw 宽, 14vh 高, 渐变 #3399ff→#66bbff→#fff
- 档位: 白色方块, 5vw

### Row 3 — 底部状态 (1/6 高度)
- 左: 里程数字 (5vw, #fff) + KM 标签
- 中: 两条叠放进度条 (BAT + FUEL)
  - BAT: 蓝色 (#3399ff) + 百分比
  - FUEL: 橙色 (#FF5722) + 百分比
  - 条背景: #0a1a2a
- 右: 时钟 HH:MM (5vw)

## 数据字段

| 字段 | 来源 | 显示 |
|:----|:----|:----|
| speed | Pi proxy / OBD | 大号数字, +3 偏置, >10 触发 |
| rpm | Pi proxy / OBD | 竖条 0-7000rpm |
| gear | calcGear(speed,rpm) | 档位数字 N/1-9 |
| heading | Pi GPS / 传感器+偏移 | 横条居中 |
| odo | Pi odometer calc | 整数 km |
| power | HA sensor.cadillac_battery | BAT 条 + % |
| fuelLevel | Pi / OBD (Pi优先) | FUEL 条 + % |
| time | Date() | HH:MM |

## 数据流
```
HUD OBD → logcat AIOBD## → Pi proxy → JSON /obd → HUD APK WebView JS
HA (OnStar/cadillac) → Pi proxy ha_data() → 合并到 JSON
HUD TYPE_ORIENTATION 传感器 → APK HudService → 合并到 heading
Pi dumpsys sensorservice 旋转矢量 → get_heading() → 合并到 heading
```

## 版本历史
- v3: 原始 4-col 3-row 布局 (初版)
- v4: 游戏式指南针横条 + 底部双层条 (2026-07-14)
