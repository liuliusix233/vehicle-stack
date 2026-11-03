# HUD 表盘 v7 — ROAD / NIGHT（2026-10-17）

在 Theme A（全能仪表）/ Theme B（纯黑大时速）之外新增两个表盘，遥控器左右键循环 A→B→C→D。
实现：`app/src/main/assets/dashboard.html`（THEMES 数组 + renderThemeC/D）。备份：`dashboard.html.bak_20261017_faces`。

## 信息分层（第一性原理）

| 层级 | 驾驶时的问题 | 停留时间 | 呈现 |
|:--|:--|:--|:--|
| 第一眼 | 我多快？ | <0.3s，中央视野 | 超大数字，位置固定 |
| 瞟一眼 | 档位对不对？还能跑多远？往哪开？ | 0.5–1s | 中号数字 / 条 / 罗盘带 |
| 查阅 | 油耗、海拔、坡度、里程、保养 | 停车或安全时 | 底部小字，低对比 |

原则：同一块屏上，字号与亮度就是优先级；不让"查阅类"信息占用第一眼区域。

## C — ROAD（长途巡航）

- 第一眼：**速度**（25vw，左）
- 第二眼：**续航 RANGE**（14.5vw，蓝）— 油量% × 82L ÷ 百公里油耗；<100km 转琥珀，<50km 转红并让标签脉冲
- 瞟一眼：档位 D1–D9、顶部连续罗盘带、油量条
- 查阅：CONS / ALT / SLOPE / ODO / TIME（底部 1.8vw 暗字）
- 版式沿用 A：顶部 5°/45°/90° 罗盘刻度带 + 中央橙色指针，同一 Odibee 字体与 #3399ff / #FF5722 色板

## D — NIGHT（夜航）

- 第一性原理：夜间瞳孔放大，**发光面积 = 眩光**。琥珀色（#FF9800，沿用 A 的坡道警示色）保暗适应，蓝色/大面积白色会破坏夜视。
- 第一眼：速度（28vw，琥珀）
- 瞟一眼：档位、续航条
- 最低亮度：罗盘方位、时间（#8a5a00）
- 刻意砍掉：RPM、油量分项、电压、CO2、ODO 大字、语音波形。需要时切回 A/B/C。
- 低续航（<80km 或油量<12%）整条转红。

## 数据契约

只使用现有 updateData 字段：speed / rpm / fuelLevel / fuelCons / odo / heading / altitude / slope /
voltage / power / chargePower / dischargePower / piOn / nanoOn / co2 / presence。
续航为前端派生值，油箱常量 `TANK_L=82`；油耗缺失时按 9.5 L/100km 估算（`CONS_FALLBACK`）。

## 验证

- Chrome 无头渲染 640×320@2x：`hud-design-refs/proto/shots/face_{a,b,c,d}.png`
- 设备 WebView 61.0.3163.98；代码只用 ES5 + let/const + padStart（Chrome 57+）
- APK 1.16 已于 2026-10-17 23:25 部署到 C2-Elite（<LAN_IP>），lastUpdateTime 复核通过
