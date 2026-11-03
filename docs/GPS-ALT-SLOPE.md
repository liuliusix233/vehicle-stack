# GPS 海拔与坡度：取值与运算

> 代码在 `software/hud/android-app/.../HudService.kt`（该目录已移至 `alternatives/c2-elite-hud/app/`）。
> 一句话：**海拔直接取 Android Location 的 `altitude`，坡度用 60 秒窗口的海拔差 ÷ 行驶距离**。
> 没有做气压计融合，也没有做地图 DEM 校正——所以坡度的绝对值只能看趋势。

## 1. GPS 取值（谁提供数据）

```kotlin
// HudService.kt:168  gpsListener
override fun onLocationChanged(loc: Location) {
    if (loc.hasBearing())  gpsBearing = loc.bearing
    if (loc.hasAltitude()) gpsAltitude = loc.altitude.toFloat()
    if (loc.hasSpeed())    gpsSpeedKmh = loc.speed * 3.6f
}
```

| 字段 | 来源 | 用途 |
| --- | --- | --- |
| `bearing` | GPS 航向（度） | HUD 朝向箭头；无 GPS 时回退到方向传感器 |
| `altitude` | WGS84 椭球高（米） | 海拔显示 + 坡度计算 |
| `speed` | GPS 地速（m/s） | 与 OBD 车速互相校验 |

Provider 是 `LocationManager.GPS_PROVIDER`（纯卫星定位，不掺网络定位）。

## 2. 海拔

直接用 `loc.altitude`，**不做任何平滑或修正**：

- HUD 上显示的是瞬时值，会跳（GPS 高程噪声典型 ±10–20 m）。
- 进隧道/地库后 `hasAltitude()` 为 false，保留上一次值（不清零）。
- 注意这是椭球高，不是海拔高（差一个 geoid 起伏，国内约 -10～-30 m），绝对值不可当真。

## 3. 坡度（`HudService.kt:847`）

```
条件：altitude 有效 且 车速 > 10 km/h
记录：(时间戳, 海拔, 车速) 进 altitudeHistory
窗口：滚动保留最近 60 秒
计算：样本 ≥5 且 窗口 ≥5s 且 行驶距离 >10 m 时
      altDelta  = avg(最后3个海拔) - avg(最前3个海拔)
      runMeters = avgSpeed(km/h) × 窗口秒数 / 3.6
      slope     = altDelta / runMeters × 100   （百分比）
过滤：|slope| > 30% → 判为噪声，置 0
复位：车速 < 5 km/h → 清空历史，slope = -1（无效）
```

### 为什么这么写

| 设计 | 原因 |
| --- | --- |
| 用首尾 3 个样本求平均 | 单点 GPS 高程噪声大，3 点平均能压掉大部分毛刺 |
| 窗口 60 秒 | 太短噪声主导，太长跟不上坡道变化 |
| 距离 >10 m 才算 | 静止或极慢时分子分母都趋近 0，会算出荒谬值 |
| 30% 上限 | 量产道路坡度极少超过 20%；超过基本是 GPS 漂移 |
| 车速 <5 清空 | 停车时海拔漂移会让坡度乱跳，直接判无效 |

## 4. 已知误差

1. **GPS 高程精度差**：平地也可能显示 ±3% 坡度。
2. 60 秒窗口意味着**坡道变化有 1 分钟延迟**（长坡没问题，短坡会糊）。
3. 没有气压计（气压高度计精度 0.1 m 级，本车没用上）。
4. 没有地图 DEM 校正（用地图高程反查可以把绝对误差压到米级）。
5. 隧道/地库无 GPS → 坡度冻结在最后有效值。

## 5. 另一条 GPS 路径（旧设备，已停用）

`alternatives/c2-elite-hud/pi-bridge/hud_gps_bridge.py` 属于 **C2-Elite HUD（车萝卜）** 时期：
那台设备**有 ADB**，脚本从 `adb logcat -s HudGPS` 读定位，再用 Haversine 累计里程推 HA。

> ⚠️ 别和车机搞混，这是两台完全不同的设备：
>
> | 设备 | 状态 | ADB | 与本文档的关系 |
> | --- | --- | --- | --- |
> | 车机 SA8155P | 现役 | **开不了**（装 App 走 U 盘） | 提供 Location/GPS 数据 |
> | C2-Elite HUD | 2026-10 退役 | 有（5555） | 本文档 §5 的旧方案，**不是**车机 |

现在车上的 HUD 是 Pi + 240×960 条屏（`software/hud/fb_dash.py`），GPS 由 Android Location 直接提供，
不再走 logcat。脚本仅作历史参考。

## 6. 想更准可以怎么做

| 方案 | 收益 | 成本 |
| --- | --- | --- |
| 加气压计（BMP280/BME280） | 高程精度到 0.1 m 级，坡度立刻可用 | 几十块 + I2C 接线 |
| GPS + 气压融合（互补滤波） | 长期不漂、短期不抖 | 代码量小 |
| 用地图 DEM 反查海拔 | 修正 GPS 系统性偏差 | 需要离线高程数据 |