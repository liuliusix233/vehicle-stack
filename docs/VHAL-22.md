# VHAL 22 属性实测（凯迪拉克 XT5 2025 / SA8155P / Android 12）

> 目的：不装 OBD、不刷机，直接从车机 `CarPropertyManager` 拿车速、挡位、油量、续航、点火、手刹。
> 本文是**实测记录**，不是抄 AOSP 文档——网上流传的 ID 在这台车上是错的（见 §4）。

## 1. 怎么拿（30 秒版）

```bash
# 1) 装 CastSink（内含 ShellBridge 命令通道）
# 本车开不了 ADB：把 CastSink.apk 拷到 U 盘，插车机手动安装
# 2) 车机与电脑同一 Wi-Fi，直接发命令
echo '#CARALL' | nc $CAR_HOST 7801
```

期望：逐行 dump 22 个属性 `id name area value`。完整输出见 §2。

## 2. 22 个属性全表

| ID | 名称 | 类型/实测值 | 备注 |
| --- | --- | --- | --- |
| 0x11100101 | INFO_MAKE | Cadillac | 字符串 |
| 0x11100102 | INFO_MODEL | Error | 厂商返回异常串 |
| 0x11200402 | PARKING_BRAKE_ON | false | 手刹 |
| 0x11200405 | FUEL_LEVEL_LOW | false | 低油位灯 |
| 0x11400103 | INFO_MODEL_YEAR | 2024 | 车机自报年款字段；实车为 2025 款 XT5，该值为厂商配置值 |
| 0x11400109 | INFO_EV_PORT_LOCATION | 1 | |
| **0x11400400** | **GEAR_SELECTION** | 位掩码 1=N 2=R 4=P 8=D | ★ 挡位选择 |
| **0x11400401** | **CURRENT_GEAR** | 16=D1 32=D2 … | ★ 实际挡位 |
| 0x11400409 | IGNITION_STATE | 4 | 4=ON |
| 0x11410105 | INFO_FUEL_TYPE | [1] | 汽油 |
| 0x11410107 | INFO_EV_CONNECTOR_TYPE | [2,6] | |
| 0x1141010b | INFO_EXTERIOR_DIMENSIONS | [2100,5166,2040,2413,2857,1564,1564,12500] | 长宽高/轴距 mm |
| 0x11510306 | WHEEL_TICK | [0,1,1,0,0] | 轮速累计 |
| 0x11600104 | INFO_FUEL_CAPACITY | 82125.0 ml | 油箱 |
| 0x11600106 | INFO_EV_BATTERY_CAPACITY | 100.0 | |
| **0x11600207** | **PERF_VEHICLE_SPEED** | 0.0 m/s | ★ 车速 |
| 0x11600208 | PERF_VEHICLE_SPEED_DISPLAY | 0.0 | 仪表显示值 |
| **0x11600307** | **FUEL_LEVEL** | 52809.99 ml | ★ 油量 |
| **0x11600308** | **RANGE_REMAINING** | 529000.0 m | ★ 续航 |
| 0x11600309 | EV_BATTERY_LEVEL | 0.0 | |
| 0x1160030c | EV_BATTERY_INSTANTANEOUS_CHARGE_RATE | 0.0 | |
| 0x1540010a | INFO_DRIVER_SEAT | 1 | |

## 3. 权限门槛（实测，决定了你能不能读）

| 权限 | 等级 | 结果 |
| --- | --- | --- |
| `android.car.permission.CAR_SPEED` | dangerous | 声明 + 运行时授权即可（self granted） |
| `android.car.permission.CAR_ENERGY` | dangerous | 可授权 |
| `android.car.permission.CAR_POWERTRAIN` | normal/dangerous | 可授权（挡位走这个） |
| `android.car.permission.CAR_INFO` | normal | 可直接读 |
| `android.car.permission.CAR_MILEAGE` | **signature\|privileged** | **读不到**（总里程无解，除非系统签名） |

## 4. ⚠️ 常量陷阱（本文档存在的原因）

下表是**车机 `android.car.jar` 里的常量定义**（静态反查得到），**不是可读属性清单**——常量存在不代表 VHAL 实现了它。本车常量与公开资料不一致：

| 常量 | 本车 | 网上常见 |
| --- | --- | --- |
| `ENGINE_RPM` | 0x11600305 | 0x11600306 |
| `PERF_VEHICLE_SPEED` | 0x11600209 | 0x11600207 |

**结论：不要硬编码 ID。** 运行时先 `getPropertyList()` 枚举，再按名字匹配；或用 `#CARALL` dump 出来后按实测值用。

## 5. 转速：四条路全死（附证据）

| 路径 | 做法 | 结果 |
| --- | --- | --- |
| VHAL | `getFloatProperty(0x11600305, 0)` | 返回 0.0，不抛异常；`getProperty` 返回 null；不在 22 个配置里 |
| 订阅 | 22 属性逐个订阅 12 秒 | **0 事件** |
| patacframework | `PowertainController.getCurrentEngineSpeed()`（原文拼写如此） | 类存在，调用被 Hidden API 拦截 |
| PatacService / FSA | 子服务与函数表 | 均为空实现或权限拒绝 |

→ 转速只能走 **OBD（ELM327）** 或 `HiddenApiBypass` 内存注入。挡位/车速用 VHAL 已经够。

## 6. 复现步骤

```bash
# a. 枚举
echo '#CARALL' | nc $CAR_HOST 7801
# b. 单属性直读（三种读法都会打印）
echo '#PROP 0x11400400 0' | nc $CAR_HOST 7801
# c. 订阅 12 秒，看变化（挂 D 挡、踩油门）
echo '#SUBS 0x11400401 12' | nc $CAR_HOST 7801
# d. 全属性订阅统计
echo '#SUBSALL 12' | nc $CAR_HOST 7801
```

## 7. 挡位解码

```kotlin
// GEAR_SELECTION 0x11400400：位掩码
fun gearSelection(v: Int) = when {
    v and 0x04 != 0 -> "P"
    v and 0x02 != 0 -> "R"
    v and 0x01 != 0 -> "N"
    v and 0x08 != 0 -> "D"
    else -> "?"
}

// CURRENT_GEAR 0x11400401：16=D1, 32=D2, 64=D3 ...（左移）
fun currentGear(v: Int): Int =
    if (v >= 16) Integer.numberOfTrailingZeros(v) - 3 else 0
```

实测样例（静止 P 挡）：`GEAR_SELECTION=4`、`CURRENT_GEAR=0`。