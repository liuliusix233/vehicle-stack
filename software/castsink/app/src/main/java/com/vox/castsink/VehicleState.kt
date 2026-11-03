package com.vox.castsink

import android.content.Context
import android.util.Log

/**
 * 数据源优先级：车机 CAN（CarProperty，直连） > Pi HTTP > HUD TCP。
 * 无数据一律 -1，UI 显示 "--"。
 */
object VehicleState {

    @Volatile var speed = -1f
    @Volatile var rpm = -1f
    @Volatile var gear = "—"
    @Volatile var gearNum = 0
    @Volatile var powerKw = -1f
    @Volatile var coolant = -1f
    @Volatile var oilLife = -1f
    @Volatile var tripKm = -1f
    @Volatile var odoKm = -1f
    @Volatile var rangeKm = -1f
    @Volatile var fuelL = -1f
    @Volatile var evPct = -1f
    @Volatile var ignition = -1
    @Volatile var parkingBrake = false
    @Volatile var engineLoad = -1f
    @Volatile var throttle = -1f
    @Volatile var outsideC = -1f
    @Volatile var live = false
    @Volatile var canCount = 0
    @Volatile var dispSpeed = -1f
    @Volatile var dispRpm = -1f
    @Volatile var dispPower = -1f

    @Volatile var carSpeed = -1f
    @Volatile var carRpm = -1f
    @Volatile var carCoolant = -1f
    @Volatile var carLoad = -1f
    @Volatile var carThrottle = -1f
    @Volatile var carOdo = -1f
    @Volatile var carGear = -1
    @Volatile var obdSpeed = -1f
    @Volatile var obdRpm = -1f
    @Volatile var obdOdoKm = -1f
    @Volatile var obdSoc = -1f
    @Volatile var obdVolt = -1f
    @Volatile var obdFuel = -1000f
    @Volatile var obdAmb = -1000f
    @Volatile var obdOil = -1000f
    @Volatile var obdIntake = -1000f
    @Volatile var obdRun = -1f
    @Volatile var obdThrottle = -1000f
    @Volatile var obdCoolant = -1000f
    @Volatile var obdLoad = -1000f
    @Volatile var soc = -1f
    @Volatile var volt12 = -1f
    @Volatile var altM = -1000f
    @Volatile var obdAltM = -1000f   // Pi 用粗定位 + 高程 API 反推的海拔
    @Volatile var lastLat = 0.0    // v113: 公开给 NavPush 内存直读，省掉每秒 #GPS socket
    @Volatile var lastLon = 0.0
    @Volatile var lastLocAt = 0L
    @Volatile var slopePct = -1000f
    @Volatile var srvKm = -1f
    @Volatile var engHrs = -1f
    // DJI 2000E（Pi BLE 直读）+ 电源总电量（本地按 2048/1008 Wh 加权，同 HA 口径）
    @Volatile var ble2000 = -1f
    @Volatile var psIn = -1f
    @Volatile var sdcIn = -1f   // 2000E 的 SDC(太阳能/直流)输入功率（W）
    @Volatile var psOut = -1f
    @Volatile var psTotalRaw = -1f
    @Volatile var psTemp = -1f
    @Volatile var psTotal = -1f
    // 预计充满时间（分钟；0 = 已满，-9999 = 无数据）
    @Volatile var psEtaMin = -9999f
    private val psHist = ArrayList<Triple<Long, Float, Float>>()
    // XT5 48V 轻混包 0.45 kWh：由 SOC 斜率反推充放功率（W，+充 -放）
    @Volatile var hybPowerW = -9999f
    // v76: 近实时功率滤波值 + 本次会话 48V 能量积分(Wh)
    private var hybWfiltered = -9999f
    private var lastSocChangeAt = 0L
    @Volatile var hybWh = 0f
    @Volatile var carHybW = -9999f   // CAN/VehicleProperty 直读的 48V 充放功率（W，+充 -放）
    private var hybPowerAt = 0L
    private val socHist = ArrayList<Pair<Long, Float>>()
    @Volatile var stale = false

    private var lastGoodAt = 0L
    private var lastGoodSpeed = -1f
    private var lastGoodRpm = -1f

    /** 8AT 参考速比：rpm per km/h（实测校准后改这里即可） */
    private val RATIO = floatArrayOf(110f, 72f, 52f, 40f, 31f, 25f, 21f, 17f)

    private var lastAt = 0L
    private var started = false
    private var gpsStarted = false
    private var geoPushed = false
    @Volatile private var gpsAltM = -1000f
    @Volatile private var gpsAltAt = 0L
    private var engineHours: EngineHourCounter? = null
    private var lastAlt = -1000f
    private var lastAltAt = 0L
    private var distSinceAlt = 0f

    fun start(ctx: Context) {
        if (started) return
        started = true
        engineHours = EngineHourCounter(ctx.getSharedPreferences("engine_hours", Context.MODE_PRIVATE))
        startGps(ctx)
        CarProps.start(ctx)
        // v69：不再做车机直连 BT —— 会和 Pi 抢同一个 ELM327（单连接适配器，抢一次掉一次）
        // ObdBt.start(ctx)
        ObdNet.start()
        HudBus.start(ctx)
    }

    fun update(now: Long) {
        val dt = if (lastAt == 0L) 0.016f else ((now - lastAt) / 1000f).coerceIn(0.001f, 0.2f)
        lastAt = now
        live = Telemetry.isFresh(now)

        // 车机 CAN 优先：直连、无网络延迟
        val useCan = carSpeed >= 0f || carRpm >= 0f
        val tSpeed = if (live) Telemetry.speed else -1f
        val tRpm = if (live && Telemetry.rpm > 50f) Telemetry.rpm else -1f

        val hudLive = HudBus.fresh(now)
        if (hudLive) {
            if (HudBus.speed >= 0f) obdSpeed = HudBus.speed
            if (HudBus.rpm >= 0f) obdRpm = HudBus.rpm
        }
        val btLive = ObdBt.connected || ObdNet.ok || hudLive
        val btSpeed = if (btLive) obdSpeed else -1f
        val btRpm = if (btLive) obdRpm else -1f
        val rawSpeed = when {
            carSpeed >= 0f -> carSpeed
            btSpeed >= 0f -> btSpeed
            tSpeed >= 0f -> tSpeed
            else -> -1f
        }
        val rawRpm = when {
            carRpm >= 0f -> carRpm
            btRpm >= 0f -> btRpm
            tRpm >= 0f -> tRpm
            else -> -1f
        }
        if (rawSpeed >= 0f) lastGoodSpeed = rawSpeed
        if (rawRpm >= 0f) lastGoodRpm = rawRpm
        if (rawSpeed >= 0f || rawRpm >= 0f) lastGoodAt = now

        // 断流保留最后有效值 15s（防 0.5–6s 抖动导致整屏闪）
        val keep = lastGoodAt > 0L && now - lastGoodAt < 15000L
        stale = keep && rawSpeed < 0f
        speed = if (rawSpeed >= 0f) rawSpeed else if (keep) lastGoodSpeed else -1f
        rpm = if (rawRpm >= 0f) rawRpm else if (keep) lastGoodRpm else -1f
        // 只用 CAN / OBD；HA 的 HUD 数据已退役，不再回退（v59）
        coolant = if (carCoolant >= 0f) carCoolant else obdCoolant
        engineLoad = if (carLoad >= 0f) carLoad else obdLoad
        throttle = if (carThrottle >= 0f) carThrottle else Telemetry.throttle
        odoKm = if (carOdo >= 0f) carOdo else if (obdOdoKm >= 0f) obdOdoKm else if (Telemetry.odo >= 0f) Telemetry.odo else -1f
        soc = if (obdSoc >= 0f) obdSoc else -1f
        volt12 = if (obdVolt >= 0f) obdVolt else -1f

        // v81: 停车/稳态一律走物理模型，SOC 斜率只在模型需要校正时用
        if (soc >= 0f) {
            HybridPower.update(dt.toDouble(), speed, rpm, throttle, engineLoad, soc)
            hybPowerW = HybridPower.powerW.toFloat()
            hybWfiltered = hybPowerW
            hybPowerAt = now
            hybWh += hybPowerW * dt / 3600f
        }
        // ── 旧 SOC 斜率反推（保留但停用，仅作参考）──
        if (false && soc >= 0f) {
            // v80: SOC 长时间不变 → 功率必须归零，否则回归斜率会凭空爬出几千瓦
            if (now - lastSocChangeAt > 2000L) {
                val k = (1f - dt * 1.6f).coerceIn(0f, 1f)
                hybWfiltered *= k
                hybPowerW *= k
                if (kotlin.math.abs(hybPowerW) < 25f) { hybPowerW = 0f; hybWfiltered = 0f }
            }
            val lastSoc = if (socHist.isEmpty()) -1f else socHist[socHist.size - 1].second
            if (socHist.isEmpty() || soc != lastSoc || now - socHist[socHist.size - 1].first >= 250L) {
                if (socHist.isNotEmpty() && soc != lastSoc) lastSocChangeAt = now
                socHist.add(now to soc)
                while (socHist.size > 24) socHist.removeAt(0)
                while (socHist.isNotEmpty() && now - socHist[0].first > 3200L) socHist.removeAt(0)
            }
            val distinctSoc = socHist.map { it.second }.distinct().size
            if (distinctSoc >= 2 && now - lastSocChangeAt < 2500L && socHist.size >= 3 && now - socHist[0].first >= 1200L) {
                val n = socHist.size
                val t0 = socHist[0].first
                var sx = 0.0
                var sy = 0.0
                for ((t, v) in socHist) { sx += (t - t0) / 1000.0; sy += v }
                val mx = sx / n
                val my = sy / n
                var num = 0.0
                var den = 0.0
                for ((t, v) in socHist) {
                    val x = (t - t0) / 1000.0 - mx
                    num += x * (v - my)
                    den += x * x
                }
                if (den > 0.05) {
                    val slope = num / den                       // %/s
                    val w = slope / 100.0 * 450.0 * 3600.0      // 0.45kWh 包
                    var clamped = w.coerceIn(-8000.0, 8000.0).toFloat()
                    if (kotlin.math.abs(clamped) < 300f) clamped = 0f
                    // v79: SOC 量化(0.39%/LSB)下斜率本质是噪声，只做"趋势"用：
                    // 一阶滤波 0.12 + 斜率限速 ±1200W/s，宁可慢半拍也不跳变
                    hybWfiltered = if (hybWfiltered < -9000f) clamped
                        else hybWfiltered + (clamped - hybWfiltered) * 0.12f
                    val maxStep = 1200f * dt
                    val prev = hybPowerW
                    if (prev < -9000f) hybPowerW = hybWfiltered
                    else hybPowerW = (prev + (hybWfiltered - prev).coerceIn(-maxStep, maxStep))
                    hybPowerW = hybPowerW.coerceIn(-4200f, 4200f)
                    hybPowerAt = now
                    hybWh += hybPowerW * dt / 3600f
                }
            }
        }
        if (hybPowerAt > 0L && now - hybPowerAt > 15000L) hybPowerW = -9999f
        // CAN 直读优先（若整车暴露 EV 充放电功率属性），否则保留 SOC 斜率估计
        // CAN 直读优先（真·实时，无 SOC 量化噪声）
        if (carHybW > -9000f) { hybPowerW = carHybW; hybWfiltered = carHybW }

        val mini = NetState.b1000
        psTotal = if (ble2000 >= 0f && mini >= 0f) (ble2000 * 2048f + mini * 1008f) / 3056f
        else if (psTotalRaw >= 0f) psTotalRaw else -1f

        // 预计充满：优先用电池包总电量增速（自带 2000E→1000mini 的转换损耗），不足再退回输入功率×效率
        updateEta(now)

        // 保养后里程 / 发动机小时（照搬 HUD EngineHourCounter 计时逻辑）
        engineHours?.tick(rpm.toInt(), odoKm)
        srvKm = engineHours?.getServiceKm(odoKm) ?: -1f
        engHrs = engineHours?.getServiceHours() ?: -1f

        // 海拔（HA/HUD GPS 优先，其次车机自带 GNSS）+ 坡度（高度差 / 里程差，4s 窗口平滑）
        val gpsAlt = if (gpsAltAt > 0L && now - gpsAltAt < 15000L) gpsAltM else -1000f
        // v72：HA 的 hud_gps_altitude 在 no_fix 时会返回 -1，而 -1 > -400 会抢走优先级 → 必须验 fix
        val haFix = NetState.altitude > -400f && NetState.gpsStatus.contains("fix", true) &&
            !NetState.gpsStatus.contains("no_fix", true)
        val haAlt = if (haFix) NetState.altitude else -1000f
        val alt = when {
            gpsAlt > -400f -> gpsAlt          // 车机自带 GNSS：连续、每秒更新，坡度最好用
            obdAltM > -400f -> obdAltM        // Pi /geo open-elevation
            haAlt > -400f -> haAlt
            else -> -1000f
        }
        val fromApi = alt > -400f && obdAltM > -400f && kotlin.math.abs(alt - obdAltM) < 0.01f
        if (alt > -400f) {
            if (lastAltAt == 0L) {
                lastAlt = alt; lastAltAt = now; distSinceAlt = 0f
            } else {
                if (speed > 0f) distSinceAlt += speed / 3.6f * dt
                if (now - lastAltAt > 4000L) {
                    val needDist = if (fromApi) 150f else 20f
                    if (distSinceAlt > needDist && lastAlt > -400f) {
                        val s = ((alt - lastAlt) / distSinceAlt * 100f).coerceIn(-30f, 30f)
                        val kSlope = if (fromApi) 0.25f else 0.4f
                        slopePct = if (slopePct < -900f) s else slopePct + (s - slopePct) * kSlope
                    }
                    lastAlt = alt; lastAltAt = now; distSinceAlt = 0f
                }
            }
            altM = alt
        } else {
            altM = -1000f
        }
        if (Telemetry.ambient >= 0f) outsideC = Telemetry.ambient

        when {
            // AIDL VehicleGear 位掩码：1=N 2=R 4=P 8=D 16=D1 32=D2 ...
            carGear == 1 -> { gearNum = 0; gear = "N" }
            carGear == 2 -> { gearNum = 0; gear = "R" }
            carGear == 4 -> { gearNum = 0; gear = "P" }
            carGear == 8 -> { gearNum = 0; gear = "D" }
            carGear >= 16 -> {
                var v = carGear; var n = 0
                while (v > 1) { v = v shr 1; n++ }
                val g = n - 3
                gearNum = if (g in 1..10) g else 0
                gear = if (gearNum > 0) "D" + gearNum else "D"
            }
            speed < 0f -> { gearNum = 0; gear = "—" }
            speed < 1.5f -> { gearNum = 0; gear = "P" }
            else -> {
                val g = calcGear(speed, rpm)
                gearNum = g
                gear = if (g <= 0) "D" else "D" + g
            }
        }

        if (speed >= 0f) {
            val byLoad = if (engineLoad >= 0f && rpm > 0f) engineLoad / 100f * 220f * (rpm / 6500f) else -1f
            powerKw = if (byLoad >= 0f) byLoad.coerceIn(0f, 320f) else -1f
        } else powerKw = -1f

        val k = (1f - Math.exp((-dt / 0.12f).toDouble())).toFloat()
        if (speed >= 0f) dispSpeed = if (dispSpeed < 0f) speed else dispSpeed + (speed - dispSpeed) * k else dispSpeed = -1f
        if (rpm >= 0f) dispRpm = if (dispRpm < 0f) rpm else dispRpm + (rpm - dispRpm) * k else dispRpm = -1f
        if (powerKw >= 0f) dispPower = if (dispPower < 0f) powerKw else dispPower + (powerKw - dispPower) * k else dispPower = -1f

        if (!useCan && !live) {
            // 两个来源都无 → 保持 -1
        }
    }

    /** 预计充满：优先总电量增速（20min 回归，自带 2000E→1000mini 转换损耗），不足退回输入功率×效率 */
    private fun updateEta(now: Long) {
        val ps = psTotal
        if (ps < 0f) { psEtaMin = -9999f; return }
        if (ps >= 99.5f) { psEtaMin = 0f; return }

        if (psHist.isEmpty() || now - psHist.last().first >= 30000L) {
            psHist.add(Triple(now, ps, if (psIn >= 0f) psIn else 0f))
            while (psHist.size > 60) psHist.removeAt(0)
            while (psHist.isNotEmpty() && now - psHist[0].first > 1200000L) psHist.removeAt(0)
        }

        // 1) 电量回归（%/min）：窗口 20min、30s 采样
        if (psHist.size >= 4 && now - psHist[0].first >= 480000L) {
            val t0 = psHist[0].first
            var sx = 0.0
            var sy = 0.0
            for ((t, v, _) in psHist) { sx += (t - t0) / 60000.0; sy += v }
            val mx = sx / psHist.size
            val my = sy / psHist.size
            var num = 0.0
            var den = 0.0
            for ((t, v, _) in psHist) {
                val xx = (t - t0) / 60000.0 - mx
                num += xx * (v - my)
                den += xx * xx
            }
            if (den > 1.0) {
                val slope = num / den   // %/min
                if (slope > 0.05) {
                    val m = (100.0 - ps) / slope
                    if (m > 0.6 && m < 900.0) { psEtaMin = m.toFloat(); return }
                }
            }
        }

        // 2) 回退：AC 输入 × 0.85 扣当前输出，按 3056Wh 包算
        val pin = psIn
        if (pin > 50f) {
            val netW = pin * 0.85f - (if (psOut > 0f) psOut else 0f)
            if (netW > 20f) {
                val remainWh = (100f - ps) / 100f * 3056f
                val m = remainWh / netW * 60f
                if (m > 0.6f && m < 900f) { psEtaMin = m; return }
            }
        }
        psEtaMin = -9999f
    }

    /**
     * 档位识别（v68）：9T50 各档「每 1000rpm 车速」（km/h），final 3.47 / 235-65R18。
     * 旧 HUD 表把「该档 ra」当成上一档的上界使用 → 整体差一档（8 显示 7）。
     * 这里改成最近邻匹配，1–9 档各自对准，不再依赖手写阈值。
     */
    private val RA_BY_GEAR = floatArrayOf(
        8.84f, 12.52f, 16.98f, 21.58f, 26.73f, 32.37f, 41.43f, 51.79f, 66.83f
    )

    private fun calcGear(speed: Float, rpm: Float): Int {
        if (speed < 1f || rpm < 100f) return 0
        val ra = speed / (rpm / 1000f)
        var best = 1
        var bestErr = Float.MAX_VALUE
        for (i in RA_BY_GEAR.indices) {
            val err = kotlin.math.abs(kotlin.math.ln(ra / RA_BY_GEAR[i]))
            if (err < bestErr) { bestErr = err; best = i + 1 }
        }
        return best
    }

    /** 车机自带 GNSS：HUD GPS 失效时兜底提供海拔 */
    private fun startGps(ctx: Context) {
        if (gpsStarted) return
        gpsStarted = true
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(loc: android.location.Location) { applyLoc(loc) }
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) { }
                override fun onProviderEnabled(p: String) { }
                override fun onProviderDisabled(p: String) { }
            }
            val gps = android.location.LocationManager.GPS_PROVIDER
            val net = android.location.LocationManager.NETWORK_PROVIDER
            // v72：先吃 lastKnownLocation（冷启动立刻有坐标）
            for (p in arrayOf(net, gps)) {
                try {
                    if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p)?.let { applyLoc(it) }
                } catch (_: Throwable) { }
            }
            // v113：GPS 1s→2s；移除 net provider 注册路；回调挂到专用 HandlerThread，不在主线程做活
            val cbThread = android.os.HandlerThread("gps-cb").apply { start() }
            if (lm.isProviderEnabled(gps)) {
                lm.requestLocationUpdates(gps, 2000L, 0f, listener, cbThread.looper)
            }
            val granted = ctx.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.i("Gps", "gps=" + lm.isProviderEnabled(gps) + " net=" + lm.isProviderEnabled(net) + " perm=" + granted)
            Thread {
                while (true) {
                    try { Thread.sleep(10000L) } catch (_: InterruptedException) { }
                    val now = System.currentTimeMillis()
                    // v113：接受窗口 30min→2min；推送附带 age（坐标生成→发送毫秒差）
                    if (lastLocAt > 0L && now - lastLocAt < 120000L) ObdNet.pushGeo(lastLat, lastLon, now - lastLocAt)
                }
            }.apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            Log.i("Gps", "unavailable: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun applyLoc(loc: android.location.Location) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        lastLat = loc.latitude; lastLon = loc.longitude; lastLocAt = System.currentTimeMillis()
        if (loc.hasAltitude()) {
            gpsAltM = loc.altitude.toFloat(); gpsAltAt = System.currentTimeMillis()
        }
        if (!geoPushed) { geoPushed = true; ObdNet.pushGeo(lastLat, lastLon, 0L) }
    }

    private object CarProps {
        private var mgr: Any? = null
        private var ids: Map<String, Int> = emptyMap()
        private var dead = false

        fun start(ctx: Context) {
            Thread {
                try {
                    val carCls = Class.forName("android.car.Car")
                    var inst: Any? = null
                    for (m in carCls.methods) {
                        if (m.name != "createCar") continue
                        try {
                            inst = if (m.parameterTypes.size == 1) m.invoke(null, ctx)
                            else m.invoke(null, ctx, null)
                            break
                        } catch (_: Throwable) { }
                    }
                    if (inst == null) { dead = true; Log.i("CarProps", "no car service"); return@Thread }
                    val gm = carCls.getMethod("getCarManager", String::class.java)
                    mgr = gm.invoke(inst, "property") ?: run { dead = true; return@Thread }
                    val vp = Class.forName("android.car.VehiclePropertyIds")
                    val map = HashMap<String, Int>()
                    for (n in listOf("PERF_VEHICLE_SPEED", "ENGINE_RPM", "GEAR",
                        "COOLANT_TEMP", "ENGINE_LOAD", "THROTTLE_POSITION",
                        "PERF_ODOMETER", "EV_BATTERY_LEVEL", "FUEL_LEVEL",
                        "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE",
                        "RANGE_REMAINING", "CURRENT_GEAR", "GEAR_SELECTION",
                        "INFO_FUEL_CAPACITY", "IGNITION_STATE", "PARKING_BRAKE_ON")) {
                        try { map[n] = vp.getField(n).getInt(null) } catch (_: Throwable) { }
                    }
                    ids = map
                    Log.i("CarProps", "connected props=" + map.size)
                    subscribe()
                    while (!dead) {
                        poll()
                        Thread.sleep(if (subOk) 250L else 50L)
                    }
                } catch (t: Throwable) {
                    dead = true
                    Log.i("CarProps", "unavailable: " + t.javaClass.simpleName + ": " + t.message)
                }
            }.apply { isDaemon = true }.start()
        }

        @Volatile var subOk = false
        fun isDead(): Boolean = dead
        fun isSubOk(): Boolean = subOk

        private fun applyProp(pid: Int, v: Any?) {
            when (pid) {
                0x11400400, 0x11400401 -> if (v is Number) VehicleState.carGear = v.toInt()
                0x11600207 -> if (v is Number) VehicleState.carSpeed = v.toFloat() * 3.6f
                0x11600208 -> if (v is Number && VehicleState.carSpeed < 0f) VehicleState.carSpeed = v.toFloat() * 3.6f
                0x11600307 -> if (v is Number) VehicleState.fuelL = v.toFloat() / 1000f
                0x11600308 -> if (v is Number) VehicleState.rangeKm = v.toFloat() / 1000f
            }
        }

        private fun subscribe() {
            try {
                val m = mgr ?: return
                val cbCls = Class.forName("android.car.hardware.property.CarPropertyManager" + "\u0024" + "CarPropertyEventCallback")
                val handler = java.lang.reflect.InvocationHandler { _, method, args ->
                    try {
                        if (method.name == "onChangeEvent" && args != null && args.isNotEmpty()) {
                            val pv = args[0]
                            val pid = pv.javaClass.getMethod("getPropertyId").invoke(pv) as Int
                            applyProp(pid, pv.javaClass.getMethod("getValue").invoke(pv))
                        }
                    } catch (_: Throwable) { }
                    null
                }
                val proxy = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf<Class<*>>(cbCls), handler)
                var reg: java.lang.reflect.Method? = null
                var argc = 0
                for (mm in m.javaClass.methods) {
                    if (mm.name == "registerCallback" && mm.parameterTypes[0] == cbCls) {
                        val n = mm.parameterTypes.size
                        if (n == 3 || n == 2) { reg = mm; argc = n; break }
                    }
                }
                if (reg == null) { Log.i("CarProps", "no registerCallback(CB,...)"); return }
                if (argc == 3) reg.invoke(m, proxy, 0f, 0f) else reg.invoke(m, proxy, 0f)
                subOk = true
                Log.i("CarProps", "subscribed argc=" + argc)
            } catch (t: Throwable) { Log.i("CarProps", "subscribe fail: " + t) }
        }

        private fun raw(name: String): Any? {
            val m = mgr ?: return null
            val id = ids[name] ?: return null
            return try {
                val meth = m.javaClass.getMethod("getProperty", Integer.TYPE, Integer.TYPE)
                val pv = meth.invoke(m, id, 0) ?: return null
                pv.javaClass.getMethod("getValue").invoke(pv)
            } catch (_: Throwable) { null }
        }

        private fun num(name: String): Float? {
            val v = raw(name)
            if (v is Number) return v.toFloat()
            if (v is Array<*> && v.isNotEmpty() && v[0] is Number) return (v[0] as Number).toFloat()
            return null
        }

        private fun poll() {
            var ok = 0
            try {
                num("PERF_VEHICLE_SPEED")?.let { if (it >= 0f) { VehicleState.carSpeed = it * 3.6f; ok++ } }
                num("ENGINE_RPM")?.let { if (it > 0f) { VehicleState.carRpm = it; ok++ } }
                num("COOLANT_TEMP")?.let { VehicleState.carCoolant = it; ok++ }
                num("ENGINE_LOAD")?.let { VehicleState.carLoad = it; ok++ }
                num("THROTTLE_POSITION")?.let { VehicleState.carThrottle = it; ok++ }
                num("PERF_ODOMETER")?.let { VehicleState.carOdo = it / 1000f; ok++ }
                // CURRENT_GEAR: 具体挡位（16=D1,32=D2）；GEAR_SELECTION: 区间（1=N,2=R,4=P,8=D）
                val curG = raw("CURRENT_GEAR") as? Number
                val selG = raw("GEAR_SELECTION") as? Number
                if (curG != null && curG.toInt() > 0) VehicleState.carGear = curG.toInt()
                else if (selG != null && selG.toInt() > 0) VehicleState.carGear = selG.toInt()
                num("FUEL_LEVEL")?.let { if (it >= 0f) { VehicleState.fuelL = it / 1000f; ok++ } }
                num("RANGE_REMAINING")?.let { if (it >= 0f) { VehicleState.rangeKm = it / 1000f; ok++ } }
                num("EV_BATTERY_LEVEL")?.let { if (it >= 0f) { VehicleState.evPct = it; ok++ } }
                (raw("IGNITION_STATE") as? Number)?.let { VehicleState.ignition = it.toInt() }
                (raw("PARKING_BRAKE_ON") as? Boolean)?.let { VehicleState.parkingBrake = it }
                num("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE")?.let {
                    if (it > -30000f && it < 30000f) { VehicleState.carHybW = it; ok++ }
                }
            } catch (_: Throwable) { }
            VehicleState.canCount = ok
        }
    }
}
