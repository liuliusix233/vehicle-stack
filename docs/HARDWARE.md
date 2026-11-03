# 硬件改造

> 软件在本仓库；硬件选型、走线、实拍都在 `hardware/` 下。

| 文档 | 内容 |
| --- | --- |
| [`hardware/BOM.md`](../hardware/BOM.md) | 选型清单：DJI Power 1000 mini、Pi Zero 2 W、240x960 条屏、4CH DVR 哨兵、Vgate 蓝牙 OBD、4G 路由棒 |
| [`hardware/INSTALL.md`](../hardware/INSTALL.md) | 取电链路、底盘走线（AD28.5 波纹管 + 丁基胶带 + 硅橡胶）、验收清单 |
| [`hardware/images/`](../hardware/images/) | 硬件实拍图 |
| [`hardware/CAD.md`](../hardware/CAD.md) | HUD 外壳主体 STL（上盖/遮光罩沿用旧件） |
| [`docs/WIRING.md`](WIRING.md) | 接线示意（极性、保险、负极接电瓶桩头） |
| [`docs/GALLERY.md`](GALLERY.md) | 实拍验收证据（HUD/电源/哨兵/摄像头） |

## 供电拓扑

```
DJI Power 1000 mini (PD 口)
  └─ PD 诱骗 → 12V DC 母线
       ├─ 哨兵 DVR（4CH 360，独立供电）
       └─ 降压模块 12V→5V 5A → Pi
```

## 安全红线

1. 接线前断开车辆 12V 负极。
2. 取电必须经保险，禁止裸接常电。
3. 穿线孔必须丁基胶带 + 硅橡胶双重密封，固化后淋水验证。
4. 改装可能影响保修与年检，自行评估。
