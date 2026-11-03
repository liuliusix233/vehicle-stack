# headunit-vhal（车机原生数据接入）

不装 OBD，直接读车机 VHAL。实现在 `../castsink/` 里（`ShellService.java` + `VehicleState.kt`），本目录是**说明 + 探测工具**。

## 能拿到什么

| 数据 | 属性 ID | 单位 |
| --- | --- | --- |
| 车速 | 0x11600207 | m/s |
| 挡位选择 | 0x11400400 | 位掩码 |
| 当前挡位 | 0x11400401 | 位掩码（16=D1） |
| 油量 | 0x11600307 | ml |
| 续航 | 0x11600308 | m |
| 点火 | 0x11400409 | 4=ON |
| 手刹 | 0x11200402 | bool |

完整 22 项与坑：`docs/VHAL-22.md`。

## 三种拿法

### 1. 命令行（最快）

```bash
./probe.sh CARALL
./probe.sh PROP 0x11400400 0
./probe.sh SUBS 0x11400401 12
```

底层就是往 ShellBridge 的 7801 端口发一行命令。

### 2. Kotlin 代码

```kotlin
val car = Car.createCar(context)
val mgr = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
// 枚举：永远先枚举，不要硬编码 ID
mgr.propertyList.forEach { p ->
    Log.i("VHAL", "0x%x %s".format(p.propertyId, p.propertyName))
}
val v = mgr.getIntProperty(0x11400400, 0)   // 挡位
```

清单里必须有：

```xml
<uses-permission android:name="android.car.permission.CAR_SPEED" />
<uses-permission android:name="android.car.permission.CAR_POWERTRAIN" />
<uses-permission android:name="android.car.permission.CAR_ENERGY" />
<uses-permission android:name="android.car.permission.CAR_INFO" />
```

`CAR_MILEAGE` 是 signature|privileged，第三方拿不到，别浪费 time。

### 3. 事件订阅（省电）

```kotlin
mgr.registerCallback(object : CarPropertyEventCallback {
    override fun onChangeEvent(e: CarPropertyValue<*>) { /* ... */ }
    override fun onErrorEvent(id: Int, zone: Int) {}
}, 0x11400401, CarPropertyManager.SENSOR_RATE_ONCHANGE)
```

注意：转速（0x11600305）订阅不到任何事件——这台车根本没把它配置进 VHAL。

## 坑清单

1. **ID 不可照抄网上资料**：本车 `ENGINE_RPM` 和 `PERF_VEHICLE_SPEED` 与 AOSP 文档差 1~2。
2. `INFO_MODEL` 返回字符串 `Error`（厂商 bug）。
3. 里程（`PERF_ODOMETER`）需要系统签名 → 无解，用 GPS 累计代替。
4. 挡位是位掩码不是枚举值，直接 `when(v)` 会错。