package com.vox.hud

/**
 * OBD sensor reading from HUD logcat AIOBD## output.
 */
data class ObdSensor(
    val key: String,
    val value: String,
    val unit: String
)

/**
 * Complete snapshot parsed from AIOBD## logcat lines.
 */
class ObdSnapshot(
    val speedKmh: Int = -1,
    val rpm: Int = -1,
    val coolantTemp: Int = -1,
    val batteryVoltage: Float = -1f,
    val fuelLevel: Int = -1,
    val engineLoad: Int = -1,
    val throttlePos: Int = -1,
    val intakeTemp: Int = -1,
    val oilTemp: Int = -1,
    val odometer: Float = -1f,
    val ambientTemp: Int = -1,
    val fuelConsumption: Float = -1f,
    val power: Int = -1,
    val chargePower: Int = -1,
    val dischargePower: Int = -1,
    // 2026-09-29: 双电源显示 —— 1000mini 电量/充电功率 + 2000E SDC(车12V)电压
    val miniPower: Int = -1,
    val miniChargePower: Int = -1,
    val sdcVoltage: Float = -1f,
    val heading: Int = -1,
    val gear: Int = 0,
    val wifiOn: Boolean = false,
    val piOn: Boolean = false,
    val nanoOn: Boolean = false,
    val altitude: Float = -1f,
    val slope: Float = -1f,
    // 2026-10-17: 车内 CO₂ 浓度 ppm (sensor.xiaomi_new3pr_8b92_co2_density) + 人体存在 (0=无人 1=有人 -1=未知)
    val co2: Int = -1,
    val presence: Int = -1,
    // 2026-10-21: 内网 2000E AC 输出状态 (1=开 0=关 -1=未知) —— 车机 Pi BLE 桥 :8095
    val acOn: Int = -1,
    val barometricPressure: Float = -1f,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Serialize to JSON for WebView injection. */
    fun toJson(): String {
        val b = StringBuilder()
        b.append('{')
        append(b, "speed", speedKmh)
        append(b, "rpm", rpm)
        append(b, "engineLoad", engineLoad)
        append(b, "throttlePos", throttlePos)
        append(b, "voltage", batteryVoltage)
        append(b, "fuelLevel", fuelLevel)
        append(b, "power", power)
        append(b, "chargePower", chargePower)
        append(b, "dischargePower", dischargePower)
        append(b, "miniPower", miniPower)
        append(b, "miniChargePower", miniChargePower)
        append(b, "sdcVoltage", sdcVoltage)
        append(b, "heading", heading)
        append(b, "odo", odometer)
        append(b, "fuelCons", fuelConsumption)
        append(b, "wifiOn", if (wifiOn) 1 else 0)
        append(b, "piOn", if (piOn) 1 else 0)
        append(b, "nanoOn", if (nanoOn) 1 else 0)
        append(b, "altitude", altitude)
        append(b, "slope", slope)
        append(b, "co2", co2)
        append(b, "presence", presence)
        append(b, "acOn", acOn)
        append(b, "baroPressure", barometricPressure)
        append(b, "connected", if (piOn) 1 else 0)
        b.setCharAt(b.length - 1, '}')
        return b.toString()
    }

    private fun append(b: StringBuilder, key: String, value: Any) {
        b.append('"').append(key).append("\":").append(value).append(',')
    }

    companion object {
        /** Parse JSON from Pi proxy. */
        fun fromJson(json: String): ObdSnapshot? {
            return try {
                // Simple JSON parsing without external libs
                val m = json.trim()
                if (!m.startsWith("{") || !m.endsWith("}")) return null
                val inner = m.substring(1, m.length - 1)

                fun extract(key: String): String? {
                    val idx = inner.indexOf("\"$key\"")
                    if (idx < 0) return null
                    val colon = inner.indexOf(':', idx + key.length + 2)
                    if (colon < 0) return null
                    var end = colon + 1
                    while (end < inner.length && inner[end] != ',' && inner[end] != '}') end++
                    return inner.substring(colon + 1, end).trim()
                }

                val speed = extract("speed")?.toIntOrNull() ?: -1
                val rpm = extract("rpm")?.toIntOrNull() ?: -1
                val coolant = extract("coolantTemp")?.toIntOrNull() ?: -1
                val voltage = extract("voltage")?.toFloatOrNull() ?: -1f
                val fuel = extract("fuelLevel")?.toIntOrNull() ?: -1
                val load = extract("engineLoad")?.toIntOrNull() ?: -1
                val throttle = extract("throttlePos")?.toIntOrNull() ?: -1
                val intake = extract("intakeTemp")?.toIntOrNull() ?: -1
                val oil = extract("oilTemp")?.toIntOrNull() ?: -1
                val odo = extract("odo")?.toFloatOrNull() ?: -1f
                val ambient = extract("ambientTemp")?.toIntOrNull() ?: -1
                val fuelCons = extract("fuelCons")?.toFloatOrNull() ?: -1f
                val power = extract("power")?.toFloatOrNull()?.toInt() ?: -1
                val heading = extract("heading")?.toFloatOrNull()?.toInt() ?: -1
                val wifiOn = (extract("wifiOn")?.toIntOrNull() ?: 0) == 1
                val piOn = (extract("connected")?.toIntOrNull() ?: 0) == 1
                val nanoOn = (extract("nanoOn")?.toIntOrNull() ?: 0) == 1
                val altitude = extract("altitude")?.toFloatOrNull() ?: -1f
                val slope = extract("slope")?.toFloatOrNull() ?: -1f
                val acOn = extract("acOn")?.toIntOrNull() ?: -1
                val baroPressure = extract("baroPressure")?.toFloatOrNull() ?: -1f

                return ObdSnapshot(
                    speedKmh = speed, rpm = rpm,
                    coolantTemp = coolant, batteryVoltage = voltage,
                    fuelLevel = fuel, engineLoad = load,
                    throttlePos = throttle, intakeTemp = intake,
                    oilTemp = oil, odometer = odo,
                    ambientTemp = ambient, fuelConsumption = fuelCons,
                    power = power, heading = heading,
                    wifiOn = wifiOn, piOn = piOn, nanoOn = nanoOn,
                    altitude = altitude, slope = slope,
                    acOn = acOn,
                    barometricPressure = baroPressure
                )
            } catch (e: Exception) {
                android.util.Log.e("ObdData", "JSON parse error: ${e.message}")
                null
            }
        }

        /** 2026-10-19: AIOBD new log format MainScreenFragment##update,StatusInfo{...}. */
        fun fromStatusInfo(line: String): ObdSnapshot? {
            fun num(key: String): Float? {
                val m = Regex("$key=(-?[0-9]+(?:\\.[0-9]+)?)").find(line)
                return m?.groupValues?.get(1)?.toFloatOrNull()
            }

            val speed = num("mObdSpeed") ?: num("mCurrentSpeed") ?: return null
            val rpm = num("mRotateSpeed") ?: -1f
            val fuelCons = num("mOilWear") ?: -1f
            val heading = num("mDirection") ?: -1f

            return ObdSnapshot(
                speedKmh = speed.toInt(),
                rpm = if (rpm >= 0) rpm.toInt() else -1,
                fuelConsumption = fuelCons,
                heading = if (heading >= 0) heading.toInt() else -1,
                wifiOn = true
            )
        }

        /** Parse AIOBD## lines and build snapshot. */
        fun fromLogcatLines(lines: List<String>): ObdSnapshot? {
            if (lines.isEmpty()) return null
            var speed = -1; var rpm = -1; var coolant = -1
            var voltage = -1f; var fuel = -1; var load = -1
            var throttle = -1; var intake = -1; var oil = -1
            var odo = -1f; var ambient = -1; var fuelCons = -1f; var baro = -1f

            for (line in lines) {
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val hash = line.indexOf("##")
                if (hash < 0) continue
                val key = line.substring(hash + 2, eq).trim()
                val valueStr = line.substring(eq + 1).trim()
                val num = valueStr.toIntOrNull()
                val fnum = valueStr.toFloatOrNull()

                when (key) {
                    "车辆速度" -> if (num != null) speed = num
                    "发动机转速", "转速" -> if (num != null) rpm = num
                    "水箱温度" -> if (num != null) coolant = num
                    "电瓶电压" -> if (fnum != null) voltage = fnum
                    "剩余油量" -> if (fnum != null) fuel = fnum.toInt() else if (num != null) fuel = num
                    "发动机负荷" -> if (num != null) load = num
                    "绝对节气门位置传感器" -> if (fnum != null) throttle = fnum.toInt() else if (num != null) throttle = num
                    "进气口温度" -> if (num != null) intake = num
                    "车辆环境温度" -> if (num != null) ambient = num
                    "行驶里程" -> if (fnum != null) odo = fnum / 1000f else if (num != null) odo = num.toFloat() / 1000f
                    "瞬时油耗" -> if (fnum != null) fuelCons = fnum
                    "发动机启动后运行时间", "进气歧管压力", "油门踏板位置", "点火提前角", "长期燃油修正" -> {}
                    "空气流量" -> {}
                    "左前胎压", "左后胎压", "右后胎压", "故障码状态", "发动机故障码个数", "OBD类型", "总里程类型", "总的燃油消耗量", "故障行驶里程", "燃油压力" -> {}
                    "大气压力" -> if (fnum != null) baro = fnum
                    "平均油耗" -> if (fnum != null) fuelCons = fnum
                }
            }

            if (speed < 0 && rpm < 0) return null
            return ObdSnapshot(
                speedKmh = speed, rpm = rpm,
                coolantTemp = coolant, batteryVoltage = voltage,
                fuelLevel = fuel, engineLoad = load,
                throttlePos = throttle, intakeTemp = intake,
                oilTemp = oil, odometer = odo,
                ambientTemp = ambient, fuelConsumption = fuelCons,
                barometricPressure = baro
            )
        }
    }
}
