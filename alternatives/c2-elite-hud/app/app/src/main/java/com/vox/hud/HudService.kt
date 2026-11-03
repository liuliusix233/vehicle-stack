package com.vox.hud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.media.MediaPlayer
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Speed offsets (2026-07-14 road test calibration):
 *   AIOS "车辆速度" ≈ dash speed at 40+, off by -2 in 10-40 range.
 *   <10 km/h: raw (avoid showing small offset at stop/creep)
 *   10-40: +2
 *   40+: +3
 */
private val SPEED_LOW_OFFSET = 2    // 10-40 km/h
private val SPEED_HIGH_OFFSET = 3   // 40+ km/h
private val SPEED_LOW_THRESHOLD = 10
private val SPEED_HIGH_THRESHOLD = 40

/**
 * AIOBD 里程刻度系数 (2026-10-06)。
 * AIOBD「行驶里程」累计值相对车机仪表盘系统性少计 ~0.45%。
 * 5 个历史校准点线性回归 slope = 1.0045 (残差 <0.8km)。
 * 显示总里程 = odoBase + AIOBD行驶里程 * ODO_SCALE。
 * 校准(无显式基数)时同样按此反推: odoBase = 目标 − AIOBD * ODO_SCALE。
 */
private const val ODO_SCALE = 1.0045f

/**
 * odoBase = 安装 HUD 时的车机总里程基数 (km)。
 * 总里程 = odoBase + AIOBD「行驶里程」累计值（车机自己累计，非单次行程）。
 * 2026-08-09: 废弃 trip delta 累加——原始数据本身就是累计值，
 * 直接相加即可，重启/闪退无损。
 */
@Volatile private var odoBase: Float = 30669.312f
/** 当前显示总里程 = odoBase + AIOBD行驶里程 (km)。 */
@Volatile private var lastGoodOdo: Float = -1f
/** Whether Nano is reachable via Tailscale. */
@Volatile private var nanoConnected: Boolean = false
/** Whether Pi is reachable via Tailscale. */
@Volatile private var piConnected: Boolean = false
/** Last known good heading (degrees, 0=N 90=E). Persisted + sensor-fallback. */
@Volatile private var lastPersistedHeading: Int = -1
/** Heading from magnetic/rotation vector sensor (degrees). */
@Volatile private var magHeading: Float = -1f


/**
 * Foreground service — Local-only mode.
 *
 * Data sources:
 *  - LocalLogcatReader  (real-time OBD via logcat, ~200ms, no root)
 *  - GPS/LocationManager (heading, altitude)
 *  - Rotation vector sensor (compass when stationary)
 *
 * Any source update triggers a broadcast to MainActivity.
 * Pi proxy / HA network dependencies removed — fully local.
 */
class HudService : Service() {

    companion object {
        private const val TAG = "HudService"
        private const val CHANNEL_ID = "hud_obd"
        private const val NOTIFY_ID = 1001

        const val ACTION_UPDATE = "com.vox.hud.UPDATE"
        const val EXTRA_JSON = "json"
        const val EXTRA_CONNECTED = "connected"

        // Calibration broadcast
        const val ACTION_CAL = "com.vox.hud.CALIBRATE"
        const val EXTRA_CAL_ODO = "odo"

        // Audio playback
        const val ACTION_PLAY_AUDIO = "com.vox.hud.PLAY_AUDIO"
        const val EXTRA_AUDIO_PATH = "audio_path"

        // Voice server configuration
        const val ACTION_SET_VOICE_SERVER = "com.vox.hud.SET_VOICE_SERVER"
        const val EXTRA_VOICE_SERVER_URL = "voice_server_url"
        const val PREFS_VOICE = "voice_config"
        const val PREFS_KEY_VOICE_URL = "voice_server_url"
        const val DEFAULT_VOICE_SERVER = "http://<LAN_IP>:8766"
        /** HA long-lived token for outdoor power battery fetch. */
        private const val HA_TOKEN = "<SECRET>"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nanoCheckInterval = 15000L
    private val logcatWatchdogInterval = 5000L

    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var localLogcatReader: LocalLogcatReader? = null
    private var vehicleDataClient: VehicleDataClient? = null

    /** Latest snapshot from local logcat (OBD data). */
    @Volatile private var latestObd: ObdSnapshot? = null
    /** Timestamp of last valid logcat snapshot (ms). */
    @Volatile private var lastLogcatMs: Long = System.currentTimeMillis()
    /** Latest outdoor power battery level from HA direct. */
    @Volatile private var latestHaBattery: ObdSnapshot? = null

    /** Latest heading from GPS bearing (or -1 if unavailable). */
    @Volatile private var gpsBearing: Float = -1f
    @Volatile private var gpsSpeedKmh: Float = -1f
    /** Latest altitude from GPS (meters, or -1 if unavailable). */
    @Volatile private var gpsAltitude: Float = -1f
    /** Altitude history for slope calculation: (timeMs, altitude_m, speed_kmh). */
    // ⚠️ 2026-08-06 FIX: was mutableListOf → ConcurrentModificationException crash
    // (broadcastMerged() called from logcat reader thread + main thread simultaneously).
    // CopyOnWriteArrayList is iteration-safe; mutations are rare (1Hz OBD).
    private val altitudeHistory = java.util.concurrent.CopyOnWriteArrayList<Triple<Long, Float, Double>>()
    /** Current road grade (%), -1 = unknown. */
    @Volatile private var currentSlope: Float = -1f

    /** Engine hour counter (post-service interval). */
    @Volatile private var engineHours: EngineHourCounter? = null

    // ── Voice Assistant ──
    @Volatile private var voiceAssistant: VoiceAssistant? = null
    @Volatile private var voiceState: String = VoiceAssistant.STATE_IDLE
    @Volatile private var voiceText: String = ""

    /** Heading persisted across restarts (SharedPreferences). */
    private var headingPrefs: android.content.SharedPreferences? = null
    /** Odometer persisted across restarts (SharedPreferences). */
    private var odoPrefs: android.content.SharedPreferences? = null

    // ── Rotation vector sensor (compass, works when stationary) ──

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val rot = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                val ori = FloatArray(3)
                SensorManager.getOrientation(rot, ori)
                // azimuth: 0=N, π/2=E, π=S, -π/2=W
                val deg = Math.toDegrees(ori[0].toDouble()).toFloat()
                magHeading = ((deg % 360f) + 360f) % 360f
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    // ── GPS heading listener ──

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (loc.hasBearing()) {
                gpsBearing = loc.bearing
            }
            if (loc.hasAltitude()) {
                gpsAltitude = loc.altitude.toFloat()
            }
            if (loc.hasSpeed()) {
                gpsSpeedKmh = loc.speed * 3.6f
            }
            if (loc.hasAccuracy()) {
                Log.d(TAG, "GPS fix: accuracy=${loc.accuracy}m alt=${if (loc.hasAltitude()) loc.altitude.toInt() else "N/A"} bearing=${if (loc.hasBearing()) loc.bearing else "N/A"}")
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            Log.d(TAG, "GPS status changed: status=$status")
        }
        override fun onProviderEnabled(provider: String?) {
            Log.d(TAG, "GPS provider enabled")
        }
        override fun onProviderDisabled(provider: String?) {
            Log.d(TAG, "GPS provider disabled")
        }
    }

    // ── Local logcat callback ──

    /** Engine-start wake trigger: was off (rpm<=0), now on (rpm>0) → notify Pi to wake Nano.
     *  2026-08-16: HUD sleeps with WiFi alive, so network-state triggers are unreliable.
     *  Engine RPM is the definitive "car just started" signal. */
    @Volatile private var wasEngineRunning = false
    @Volatile private var lastWakeNotifyMs = 0L
    private val wakeNotifyCooldownMs = 30000L

    private fun maybeNotifyEngineStart() {
        val rpm = latestObd?.rpm ?: -1
        if (rpm > 0 && !wasEngineRunning) {
            // Engine just started — tell Pi (LAN, stable IP) to wake Nano if asleep.
            val now = System.currentTimeMillis()
            if (now - lastWakeNotifyMs > wakeNotifyCooldownMs) {
                lastWakeNotifyMs = now
                Log.i(TAG, "🚗 Engine start detected (rpm=$rpm) → notify Pi to wake Nano")
                object : Thread("WakeNanoNotify") {
                    override fun run() {
                        try {
                            // Pi LAN endpoint: GET /nano/boot (idempotent — no-op if Nano online)
                            val url = java.net.URL("http://<LAN_IP>:8899/nano/boot")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            conn.requestMethod = "GET"
                            val code = conn.responseCode
                            Log.d(TAG, "WakeNano notify → HTTP $code")
                            conn.disconnect()
                        } catch (e: Exception) {
                            Log.w(TAG, "WakeNano notify failed: ${e.message}")
                        }
                    }
                }.start()
            }
        }
        wasEngineRunning = rpm > 0
    }

    private val onLocalSnapshot: (ObdSnapshot) -> Unit = { snapshot ->
        // Feed engine hour counter (RPM > 0 = engine running)
        engineHours?.tick(snapshot.rpm, snapshot.odometer)
        latestObd = snapshot
        lastLogcatMs = System.currentTimeMillis()
        maybeNotifyEngineStart()
        // ⚠️ 2026-08-06 FIX: must hop to main thread — broadcastMerged() touches
        // altitudeHistory which is also mutated on the main thread (GPS/battery/Nano
        // checks). Direct call from the logcat reader thread caused
        // ConcurrentModificationException → app crash (闪退).
        mainHandler.post { broadcastMerged() }
    }

    // ── HA outdoor power battery poll (direct, no Pi proxy) ──

    private val haPowerTimer = java.util.Timer("HaPowerTimer", true)

    /** Periodic crash-safe persist of odo + engine hours (every 5s).
     *  Guarantees on-disk state is never older than 5s even with no trip delta. */
    private val odoPersistTask = object : Runnable {
        override fun run() {
            persistOdo()
            engineHours?.persist()
            mainHandler.postDelayed(this, 5000)
        }
    }

    // ── Logcat watchdog ──

    private val logcatWatchdogTask = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - lastLogcatMs
            if (elapsed > logcatWatchdogInterval) {
                Log.w(TAG, "Logcat stalled for ${elapsed}ms, restarting...")
                localLogcatReader?.stop()
                localLogcatReader = LocalLogcatReader(onLocalSnapshot)
                localLogcatReader?.start()
            }
            mainHandler.postDelayed(this, logcatWatchdogInterval)
        }
    }

    // ── Tailscale health checks ──

    private val piCheckTask = object : Runnable {
        override fun run() {
            checkPiDirect()
            mainHandler.postDelayed(this, nanoCheckInterval)
        }
    }

    private val nanoCheckTask = object : Runnable {
        override fun run() {
            checkNanoDirect()
            mainHandler.postDelayed(this, nanoCheckInterval)
        }
    }

    // ── Lifecycle ──

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate — local-only mode")
        createNotificationChannel()

        // ⚠️ 2026-08-09: crash-proofing — persist critical state synchronously
        // before the system kills the process on an uncaught crash, then delegate
        // to the previous handler so crash logs still land in logcat.
        val prevCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "💥 Uncaught crash on ${thread.name}: ${throwable.message}")
            try { persistOdo() } catch (_: Exception) {}
            try { engineHours?.persist() } catch (_: Exception) {}
            prevCrashHandler?.uncaughtException(thread, throwable)
        }

        // Initialize engine hour counter
        engineHours = EngineHourCounter(getSharedPreferences("engine_hours", MODE_PRIVATE))
        Log.d(TAG, "EngineHours init: ${engineHours?.getServiceHours()}h post-service")

        // Load persisted heading (survives restarts)
        headingPrefs = getSharedPreferences("compass", MODE_PRIVATE)
        lastPersistedHeading = headingPrefs?.getInt("last_heading", -1) ?: -1
        if (lastPersistedHeading >= 0) {
            Log.d(TAG, "Loaded persisted heading: $lastPersistedHeading°")
        }

        // Load persisted odometer base (survives restarts)
        odoPrefs = getSharedPreferences("odometer", MODE_PRIVATE)
        val savedBase = odoPrefs?.getFloat("odo_base", -1f) ?: -1f
        if (savedBase > 0) {
            odoBase = savedBase
            Log.d(TAG, "Loaded persisted odo base: $odoBase km")
        }
        val savedOdo = odoPrefs?.getFloat("last_good_odo", -1f) ?: -1f
        if (savedOdo > 0) {
            lastGoodOdo = savedOdo
            Log.d(TAG, "Loaded persisted odo: $savedOdo km (base=$odoBase)")
        }

        // Start local logcat reader (real-time OBD)
        localLogcatReader = LocalLogcatReader(onLocalSnapshot)
        localLogcatReader?.start()

        // 2026-10-19: bind to aicore DataRepertoryService (same source as stock desktop)
        vehicleDataClient = VehicleDataClient { snapshot -> onLocalSnapshot(snapshot) }
        vehicleDataClient?.start(this)

        // Initialize voice assistant (continuous listening)
        try {
            // Voice server: configurable via SharedPreferences or broadcast
            val voicePrefs = getSharedPreferences(PREFS_VOICE, MODE_PRIVATE)
            val voiceServerUrl = voicePrefs.getString(PREFS_KEY_VOICE_URL, DEFAULT_VOICE_SERVER)
                ?: DEFAULT_VOICE_SERVER
            Log.d(TAG, "Voice server URL: $voiceServerUrl")
            voiceAssistant = VoiceAssistant(voiceServerUrl) { state, text, ttsUrl ->
                voiceState = state
                voiceText = text
                if (state == VoiceAssistant.STATE_SPEAKING && ttsUrl != null) {
                    downloadAndPlayTts(ttsUrl)
                }
                broadcastMerged()
            }
            voiceAssistant?.start()
            Log.d(TAG, "Voice assistant started (server=$voiceServerUrl)")
        } catch (e: Exception) {
            Log.w(TAG, "Voice assistant init failed: ${e.message}")
        }

        // GPS bearing for heading (orientation sensor TYPE_ORIENTATION broken on C2-Elite)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 200L, 1f, gpsListener
            )
            Log.d(TAG, "GPS heading listener registered")
        } catch (e: SecurityException) {
            Log.w(TAG, "No GPS permission")
        } catch (e: Exception) {
            Log.w(TAG, "GPS init error: ${e.message}")
        }

        // Rotation vector sensor (compass fallback, works when stationary)
        // ⚠️ 2026-08-05 DISABLED: MTK sensor HAL (android.hardware.sensors@1.0-service-mediatek)
        //    dumps AF9133 magnetometer debug logs @50Hz while any app reads the mag sensor,
        //    flooding logd main buffer (20MB in ~13min) → AIOBD data dropped → HUD stalls + odo loss.
        //    Heading now uses GPS bearing + persisted heading only (magHeading stays -1).
        //    To re-enable: uncomment the registerListener block below.
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        // val rvSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        // if (rvSensor != null) {
        //     sensorManager?.registerListener(
        //         compassListener, rvSensor, SensorManager.SENSOR_DELAY_UI
        //     )
        //     Log.d(TAG, "Rotation vector sensor registered")
        // } else {
        //     Log.w(TAG, "No rotation vector sensor (C2-Elite) — compass relies on GPS+bearing only")
        // }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        // Handle audio playback
        if (intent?.action == ACTION_PLAY_AUDIO) {
            val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
            if (audioPath != null) {
                playAudioFile(audioPath)
            }
            return START_STICKY
        }

        // Handle voice server URL update
        if (intent?.action == ACTION_SET_VOICE_SERVER) {
            val newUrl = intent.getStringExtra(EXTRA_VOICE_SERVER_URL)
            if (newUrl != null && newUrl.isNotBlank()) {
                getSharedPreferences(PREFS_VOICE, MODE_PRIVATE).edit()
                    .putString(PREFS_KEY_VOICE_URL, newUrl).apply()
                Log.d(TAG, "Voice server URL updated: $newUrl (restart service to apply)")
            }
            return START_STICKY
        }

        // Handle calibration broadcast
        if (intent?.action == ACTION_CAL) {
            val calOdo = intent.getFloatExtra(EXTRA_CAL_ODO, -1f)
            // 2026-08-13: 显式基数支持 (修复熄火状态误覆盖基数, 最高优先级)
            val calBase = intent.getFloatExtra("odoBase", -1f)
            if (calOdo > 0) {
                // 校准 = 设总里程。反推基数：odoBase = 目标总里程 - 当前AIOBD累计值
                if (calBase > 0) {
                    // 显式基数 (远程修复/微调用, 最高优先级, 无需 AIOBD)
                    odoBase = calBase
                    Log.w(TAG, "CALIBRATE: explicit base=$odoBase")
                } else {
                    val aiobd = latestObd?.odometer ?: -1f
                    if (aiobd > 0) {
                        odoBase = calOdo - aiobd * ODO_SCALE
                    } else if (lastGoodOdo > 0) {
                        // 无 AIOBD 也无显式基数: 增量调整基数, 不覆盖 (修复 2026-08-13 bug)
                        val delta = calOdo - lastGoodOdo
                        odoBase += delta
                        Log.w(TAG, "CALIBRATE: no AIOBD, base += ${delta} → $odoBase")
                    } else {
                        // 全新无基线, 只能直接设 (极少见)
                        odoBase = calOdo
                        Log.w(TAG, "CALIBRATE: no AIOBD & no baseline, base set to $odoBase")
                    }
                }
                lastGoodOdo = calOdo
                persistOdo()
                Log.d(TAG, "Odometer calibrated to: $calOdo (base=$odoBase, persisted)")
                mainHandler.post { broadcastMerged() }  // Push UI update immediately
            }
            // Engine hours calibration
            val calHours = intent.getFloatExtra("engHrs", -1f)
            if (calHours > 0) {
                engineHours?.calibrateHours(calHours)
                Log.d(TAG, "Engine hours calibrated to: ${calHours}h")
            }
            // New service reset
            val isNewService = intent.getBooleanExtra("newService", false)
            if (isNewService && calOdo > 0) {
                engineHours?.resetForNewService(calOdo)
                Log.d(TAG, "New service reset at odo: $calOdo")
            }
            return START_STICKY  // Don't re-init on calibration
        }

        startForeground(NOTIFY_ID, buildNotification())
        // Initial broadcast (may be stale or fallback)
        broadcastMerged()
        // Start HA power poll (outdoor battery, every 5s via Timer)
        haPowerTimer.schedule(object : java.util.TimerTask() {
            override fun run() { fetchHaBattery() }
        }, 500L, 5000L)
        // Start logcat watchdog (restarts reader if stalled >5s)
        mainHandler.removeCallbacks(logcatWatchdogTask)
        mainHandler.postDelayed(logcatWatchdogTask, 5000)
        // Periodic crash-safe persist
        mainHandler.removeCallbacks(odoPersistTask)
        mainHandler.postDelayed(odoPersistTask, 5000)

        // Start Tailscale health checks
        piConnected = false
        nanoConnected = false
        mainHandler.removeCallbacks(piCheckTask)
        mainHandler.removeCallbacks(nanoCheckTask)
        mainHandler.postDelayed(piCheckTask, 50)
        mainHandler.postDelayed(nanoCheckTask, 100)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        persistHeading()
        persistOdo()
        engineHours?.persist()
        haPowerTimer.cancel()
        mainHandler.removeCallbacks(logcatWatchdogTask)
        mainHandler.removeCallbacks(odoPersistTask)
        mainHandler.removeCallbacks(piCheckTask)
        mainHandler.removeCallbacks(nanoCheckTask)
        localLogcatReader?.stop()
        vehicleDataClient?.stop()
        voiceAssistant?.stop()
        try { locationManager?.removeUpdates(gpsListener) } catch (_: Exception) {}
        try { sensorManager?.unregisterListener(compassListener) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Persist heading to survive restarts ──

    private fun persistHeading() {
        if (lastPersistedHeading < 0) return
        try {
            headingPrefs?.edit()?.putInt("last_heading", lastPersistedHeading)?.apply()
        } catch (_: Exception) {}
    }

    // ── Persist odometer baseline to survive restarts ──

    private fun persistOdo() {
        if (lastGoodOdo <= 0 && odoBase <= 0) return
        try {
            with(odoPrefs?.edit() ?: return) {
                putFloat("odo_base", odoBase)
                putFloat("last_good_odo", lastGoodOdo)
                commit()  // 2026-08-09: synchronous flush — apply() is async and loses data on crash
            }
        } catch (_: Exception) {}
    }

    // ── Direct Nano LAN ping check ──

    /**
     * Check if Nano is online via car-LAN ping (<LAN_IP> = Nano br0).
     * 2026-08-18: Tailscale TCP socket check was unreliable (HUD TS flaky,
     * IP was stale <TS_IP>) → switched to LAN ICMP ping.
     */
    private fun checkNanoDirect() {
        Log.d(TAG, "NanoCheck scheduled")
        object : Thread("NanoCheck") {
            override fun run() {
                Log.d(TAG, "NanoCheck running")
                val wasOnline = nanoConnected
                val online = pingHost("<LAN_IP>")
                if (online != wasOnline) {
                    Log.d(TAG, "Nano LAN: ${if (online) "ONLINE" else "OFFLINE"}")
                } else if (online) {
                    Log.d(TAG, "Nano LAN: still ONLINE (known)")
                }
                nanoConnected = online
                mainHandler.post { broadcastMerged() }
            }
        }.start()
    }

    /** ICMP ping a host via toybox ping; true = reachable. */
    private fun pingHost(host: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "2", host))
            val code = p.waitFor()
            try { p.destroy() } catch (_: Exception) {}
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "ping $host failed: ${e.message}")
            false
        }
    }

    // ── Direct Pi LAN ping check ──

    private fun checkPiDirect() {
        object : Thread("PiCheck") {
            override fun run() {
                val wasOnline = piConnected
                val online = pingHost("<LAN_IP>")
                if (online != wasOnline) {
                    Log.d(TAG, "Pi LAN: ${if (online) "ONLINE" else "OFFLINE"}")
                } else if (online) {
                    Log.d(TAG, "Pi LAN: still ONLINE (known)")
                }
                piConnected = online
                mainHandler.post { broadcastMerged() }
            }
        }.start()
    }

    /**
     * Fetch outdoor power battery level directly from HA REST API.
     * Inlined to avoid external class loading issues on HUD Android 8.1.
     */
    private fun fetchHaBattery() {
        Log.w(TAG, "⚡ poll")
        object : Thread("HaBatteryFetch") {
            override fun run() {
                try {
                    var power = -1
                    var chargePower = -1
                    var dischargePower = -1
                    var miniPower = -1
                    var miniChargePower = -1
                    var sdcVoltage = -1f
                    var co2 = -1
                    var presence = -1
                    var acOn = -1
                    // ⚠️ 2026-08-13: public HA (nas.<PRIVATE_DOMAIN>) first, Pi proxy fallback
                    // (HUD Tailscale/Pi 不稳时, 公网 HA 直连仍可显示户外电源)
                    // 2026-09-05: 8123 明文已关闭 → 改走 https://nas.<PRIVATE_DOMAIN>:8443 (Caddy 反代, ZeroSSL 证书兼容 Android 8.1)
                    val haEntities = arrayOf(
                        // 2026-10-18: power 改用车辆电池总电量(原 sensor.cadillac_battery 户外电源)
                        "sensor.vehicle_battery_total",
                        "sensor.cadillac_charge_power",
                        "sensor.cadillac_discharge_power",
                        "sensor.mini_power_battery",
                        "sensor.cadillac_sdc_voltage",
                        "sensor.mini_power_discharge_power",
                        "sensor.mini_power_charge_power",
                        // 2026-10-17: 车内 CO₂ + 人体存在 (Xiaomi Human Presence Sensor Pro)
                        "sensor.xiaomi_new3pr_8b92_co2_density",
                        "sensor.xiaomi_p1_852a_occupancy_sensor"
                    )
                    val haVals = FloatArray(9) { -1f }
                    val haRaw = arrayOfNulls<String>(9)
                    var haGot = false
                    for (i in haEntities.indices) {
                        try {
                            val u = "https://nas.<PRIVATE_DOMAIN>:8443/api/states/" + haEntities[i]
                            val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 2500
                            conn.readTimeout = 2500
                            conn.requestMethod = "GET"
                            conn.setRequestProperty("Authorization", "Bearer $HA_TOKEN")
                            val code = conn.responseCode
                            if (code == 200) {
                                val body = conn.inputStream.bufferedReader().use { it.readText() }
                                // HA state JSON: {"entity_id":"...","state":"81.0",...}
                                val si = body.indexOf("\"state\"")
                                if (si >= 0) {
                                    val colon = body.indexOf(':', si + 7)
                                    if (colon >= 0) {
                                        var s = colon + 1
                                        while (s < body.length && (body[s] == ' ' || body[s] == '"')) s++
                                        var e = s
                                        while (e < body.length && body[e] != '"' && body[e] != ',' && body[e] != '}') e++
                                        val raw = body.substring(s, e).trim()
                                        haRaw[i] = raw
                                        haVals[i] = raw.toFloatOrNull() ?: -1f
                                        if (haVals[i] >= 0) haGot = true
                                    }
                                }
                            } else {
                                Log.w(TAG, "HA public $u HTTP $code")
                            }
                            conn.disconnect()
                        } catch (e: Exception) {
                            Log.w(TAG, "HA public err: ${e.message}")
                        }
                    }
                    // 2026-10-21: 2000E AC 输出状态 —— 车机内网 Pi BLE 桥 (不走公网)
                    try {
                        val acUrls = arrayOf(
                            "http://<LAN_IP>:8095/state",
                            "http://<TS_IP>:8095/state"
                        )
                        for (u in acUrls) {
                            try {
                                val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 1500
                                conn.readTimeout = 1500
                                conn.requestMethod = "GET"
                                if (conn.responseCode == 200) {
                                    val body = conn.inputStream.bufferedReader().use { it.readText() }.replace(" ", "")
                                    val pi = body.indexOf("\"ports\"")
                                    val ki = if (pi >= 0) body.indexOf("\"2-1\"", pi) else -1
                                    if (ki >= 0) {
                                        val ci = body.indexOf(':', ki + 5)
                                        if (ci >= 0) {
                                            val tail = body.substring(ci + 1, minOf(body.length, ci + 8))
                                            acOn = if (tail.startsWith("true")) 1 else 0
                                        }
                                    }
                                }
                                conn.disconnect()
                                if (acOn >= 0) break
                            } catch (e: Exception) {
                                Log.w(TAG, "AC bridge $u err: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AC bridge err: ${e.message}")
                    }
                    if (acOn >= 0) Log.w(TAG, "⚡ 2000E AC=${if (acOn == 1) "ON" else "OFF"} (intranet)")
                    if (haGot) {
                        // 2026-09-30: 单条电量 = 两机合计能量 / 3056Wh (2000E 2048Wh + 1000mini 1008Wh)
                        val p2000 = haVals[0]
                        val p1000 = haVals[3]
                        power = if (p2000 >= 0f && p1000 >= 0f) {
                            ((2048f * p2000 / 100f + 1008f * p1000 / 100f) / 3056f * 100f).toInt()
                        } else if (p2000 >= 0f) {
                            p2000.toInt()
                        } else {
                            -1
                        }
                        chargePower = haVals[1].toInt()
                        // 2026-09-29: OUT = 两台机器总输出功率 (2000E + 1000mini)
                        val outA = if (haVals[2] >= 0f) haVals[2] else 0f
                        val outB = if (haVals[5] >= 0f) haVals[5] else 0f
                        dischargePower = if (haVals[2] >= 0f || haVals[5] >= 0f) (outA + outB).toInt() else -1
                        miniPower = haVals[3].toInt()
                        sdcVoltage = haVals[4]
                        miniChargePower = haVals[6].toInt()
                        // 2026-10-17: 车内 CO₂ 浓度 + 人体存在
                        co2 = haVals[7].toInt()
                        presence = when (haRaw[8]) {
                            null, "unavailable" -> -1
                            "no one", "vacant", "not_home" -> 0
                            else -> 1
                        }
                        Log.w(TAG, "⚡ public HA: power=$power% (2000=${haVals[0]}% mini=${haVals[3]}%) " +
                                "in=${chargePower}W out=${dischargePower}W (a=${haVals[2]} b=${haVals[5]}) " +
                                "sdc=${sdcVoltage}V co2=${co2}ppm presence=$presence")
                    } else {
                        // ── Pi proxy 回退 (原 2026-08-09 逻辑) ──
                        val urls = arrayOf(
                            "http://<LAN_IP>:8080/obd",
                            "http://<TS_IP>:8080/obd"
                        )
                        var json: String? = null
                        for (u in urls) {
                            try {
                                val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 2500
                                conn.readTimeout = 2500
                                conn.requestMethod = "GET"
                                val code = conn.responseCode
                                if (code == 200) {
                                    json = conn.inputStream.bufferedReader().use { it.readText() }
                                } else {
                                    Log.w(TAG, "Pi proxy $u HTTP $code")
                                }
                                conn.disconnect()
                                if (json != null) break
                            } catch (e: Exception) {
                                Log.w(TAG, "Pi proxy $u err: ${e.message}")
                            }
                        }
                        if (json != null) {
                            val m = json.trim()
                            // Extract numeric fields from Pi proxy JSON (no JSON lib on API 27)
                            fun extractNum(key: String): Int {
                                val keyIdx = m.indexOf("\"$key\"")
                                if (keyIdx < 0) return -1
                                val colonIdx = m.indexOf(':', keyIdx + key.length + 2)
                                if (colonIdx < 0) return -1
                                var start = colonIdx + 1
                                while (start < m.length && m[start] == ' ') start++
                                val raw = if (start < m.length && m[start] == '"') {
                                    val end = m.indexOf('"', start + 1)
                                    if (end > start) m.substring(start + 1, end) else null
                                } else {
                                    var end = start
                                    while (end < m.length && m[end] != ',' && m[end] != '}') end++
                                    m.substring(start, end).trim()
                                }
                                return raw?.toFloatOrNull()?.toInt() ?: -1
                            }
                            power = extractNum("power")
                            chargePower = extractNum("chargePower")
                            dischargePower = extractNum("dischargePower")
                        }
                    }

                    if (power >= 0 || chargePower >= 0 || dischargePower >= 0 || co2 >= 0 || acOn >= 0) {
                        latestHaBattery = ObdSnapshot(
                            power = power,
                            chargePower = chargePower,
                            dischargePower = dischargePower,
                            miniPower = miniPower,
                            miniChargePower = miniChargePower,
                            sdcVoltage = sdcVoltage,
                            co2 = co2,
                            presence = presence,
                            acOn = if (acOn >= 0) acOn else (latestHaBattery?.acOn ?: -1)
                        )
                        Log.w(TAG, "⚡ power=$power% in=${chargePower}W out=${dischargePower}W co2=${co2} presence=$presence")
                    }
                    mainHandler.post { broadcastMerged() }
                } catch (e: Exception) {
                    Log.w(TAG, "⚡ err: ${e.message}")
                }
            }
        }.start()
    }

    // ── Merge & broadcast ──

    /**
     * Build merged OBD snapshot from local-only sources.
     * Runs on main thread; called from logcat callback, compass, GPS,
     * nano check, and HA battery poll.
     */
    private fun broadcastMerged() {
        try {
        val obd = latestObd

        // ── 1. Speed: per-range offset (calibrated 2026-07-14) ──
        val obdSpeed = obd?.speedKmh ?: -1
        // 2026-10-19: fall back to GPS speed when AIOBD is not reporting
        val rawSpeed = if (obdSpeed >= 0) obdSpeed else gpsSpeedKmh.toInt()
        val correctedSpeed = when {
            rawSpeed < SPEED_LOW_THRESHOLD -> rawSpeed
            rawSpeed <= SPEED_HIGH_THRESHOLD -> rawSpeed + SPEED_LOW_OFFSET
            else -> rawSpeed + SPEED_HIGH_OFFSET
        }

        // ── 2. Fuel: local OBD only ──
        val correctedFuel = obd?.fuelLevel?.takeIf { it > 0 } ?: -1

        // ── 3. Heading: GPS bearing > rotation vector > persisted > -1 ──
        val rawHeading: Int = if (gpsBearing >= 0) {
            gpsBearing.toInt()
        } else if (magHeading >= 0) {
            magHeading.toInt()
        } else {
            lastPersistedHeading.takeIf { it >= 0 } ?: -1
        }
        if (rawHeading >= 0 && rawHeading != lastPersistedHeading) {
            lastPersistedHeading = rawHeading
            persistHeading()
        }

        // ── 4. 里程：odoBase + AIOBD累计行驶里程 × ODO_SCALE（刻度补偿）──
        val aiobdOdo = obd?.odometer
        if (aiobdOdo != null && aiobdOdo > 0 && odoBase > 0) {
            val total = odoBase + aiobdOdo * ODO_SCALE
            // ⚠️ 棘轮保护 (2026-09-07): AIOBD 源进程崩溃/旧检查点重放会让行驶里程
            // 周期性回跳几百米 (实测单次最大 -468m)。屏显只增不减——回跳时保持
            // lastGoodOdo 不跟随下跌, 源恢复后 total 自然追平。真实车总里程单调增,
            // 回跳必为源端伪影; 若源真重置(换设备等), 用 CALIBRATE 显式校准绕过。
            if (total > lastGoodOdo + 0.005f) {
                lastGoodOdo = total
                persistOdo()
                Log.d(TAG, "Odo: base $odoBase + aiobd $aiobdOdo = $total km")
            } else if (total < lastGoodOdo - 0.005f) {
                Log.w(TAG, "Odo ratchet: aiobd dipped ${lastGoodOdo - total} km (raw=$aiobdOdo), holding $lastGoodOdo")
            }
        }
        val correctedOdo = if (lastGoodOdo > 0) lastGoodOdo else -1f

        // ── 5. Altitude + Slope (GPS) ──
        val currentAltitude = gpsAltitude

        if (currentAltitude >= 0 && correctedSpeed > 10) {
            altitudeHistory.add(Triple(System.currentTimeMillis(), currentAltitude, correctedSpeed.toDouble()))
            val cutoff = System.currentTimeMillis() - 60_000
            while (altitudeHistory.size > 1 && altitudeHistory.first().first < cutoff) {
                altitudeHistory.removeAt(0)
            }
            if (altitudeHistory.size >= 5) {
                val first = altitudeHistory.first()
                val last = altitudeHistory.last()
                val firstAvg = altitudeHistory.take(3).map { it.second }.average()
                val lastAvg = altitudeHistory.takeLast(3).map { it.second }.average()
                val windowMs = last.first - first.first
                if (windowMs >= 5_000) {
                    val altDelta = (lastAvg - firstAvg).toFloat()
                    val avgSpeed = altitudeHistory.map { it.third }.average()
                    val runMeters = avgSpeed * windowMs / 1000.0 / 3.6
                    if (runMeters > 10) {
                        currentSlope = ((altDelta / runMeters.toFloat()) * 100).toFloat()
                        if (kotlin.math.abs(currentSlope) > 30f) currentSlope = 0f
                    }
                }
            }
        } else if (altitudeHistory.isNotEmpty() && correctedSpeed < 5) {
            altitudeHistory.clear()
            currentSlope = -1f
        }

        Log.d(TAG, "Alt/Slope: ${currentAltitude}m / ${currentSlope}%")

        val merged = ObdSnapshot(
            speedKmh       = correctedSpeed,
            rpm            = obd?.rpm ?: -1,
            coolantTemp    = obd?.coolantTemp ?: -1,
            batteryVoltage = obd?.batteryVoltage ?: -1f,
            fuelLevel      = correctedFuel,
            engineLoad     = obd?.engineLoad ?: -1,
            throttlePos    = obd?.throttlePos ?: -1,
            intakeTemp     = obd?.intakeTemp ?: -1,
            oilTemp        = obd?.oilTemp ?: -1,
            odometer       = correctedOdo,
            ambientTemp    = obd?.ambientTemp ?: -1,
            fuelConsumption = obd?.fuelConsumption ?: -1f,
            power          = latestHaBattery?.power?.takeIf { it >= 0 } ?: -1,
            chargePower    = latestHaBattery?.chargePower?.takeIf { it >= 0 } ?: -1,
            dischargePower = latestHaBattery?.dischargePower?.takeIf { it >= 0 } ?: -1,
            miniPower      = latestHaBattery?.miniPower?.takeIf { it >= 0 } ?: -1,
            miniChargePower = latestHaBattery?.miniChargePower?.takeIf { it >= 0 } ?: -1,
            sdcVoltage     = latestHaBattery?.sdcVoltage?.takeIf { it >= 0f } ?: -1f,
            co2            = latestHaBattery?.co2 ?: -1,
            presence       = latestHaBattery?.presence ?: -1,
            acOn           = latestHaBattery?.acOn ?: -1,
            heading        = rawHeading,
            gear           = obd?.gear ?: 0,
            wifiOn         = obd != null,
            piOn           = piConnected,  // Tailscale TCP check
            nanoOn         = nanoConnected,
            altitude       = currentAltitude,
            slope          = currentSlope
        )

        // ── 6. Engine hours since service ──
        val engHrsVal = engineHours?.getServiceHours() ?: -1f
        val srvKmVal = engineHours?.getServiceKm(correctedOdo) ?: -1f

        SpeedServer.start()
        SpeedBroadcaster.send(merged.speedKmh, merged.rpm)
        SpeedServer.send(merged.speedKmh, merged.rpm)

        Log.d(TAG, "Broadcast merged: speed=${merged.speedKmh} rpm=${merged.rpm} " +
                "odo=${merged.odometer} fuel=${merged.fuelLevel} " +
                "heading=${merged.heading} engHrs=${engHrsVal}srvKm=${srvKmVal}")

        // Inject engine hour and voice state fields into JSON
        val baseJson = merged.toJson()
        var enhancedJson = if (engHrsVal > 0 || srvKmVal > 0) {
            baseJson.dropLast(1) + ",\"engHrs\":${engHrsVal},\"srvKm\":${srvKmVal},"
        } else {
            baseJson.dropLast(1) + ","
        }
        // Add voice state
        enhancedJson += "\"voiceState\":\"${voiceState}\",\"voiceText\":\"${escapeJson(voiceText)}\"}"

        sendBroadcast(Intent(ACTION_UPDATE).apply {
            putExtra(EXTRA_JSON, enhancedJson)
            putExtra(EXTRA_CONNECTED, 1)
        })
        } catch (t: Throwable) {
            // ⚠️ 2026-08-09: never let a single bad frame kill the service
            // (a crash here = odo accumulation stops = mileage loss)
            Log.e(TAG, "broadcastMerged error: ${t.message}", t)
        }
    }

    // ── TTS Download + Playback ──

    /**
     * Download TTS audio from server and play it through the HUD speaker.
     */
    private fun downloadAndPlayTts(url: String) {
        object : Thread("TtsDownload") {
            override fun run() {
                try {
                    val ttsDir = java.io.File(filesDir, "tts")
                    ttsDir.mkdirs()
                    val ttsFile = java.io.File(ttsDir, "vox_${System.currentTimeMillis()}.wav")
                    
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    conn.connect()
                    
                    ttsFile.outputStream().use { out ->
                        conn.inputStream.use { inp -> inp.copyTo(out) }
                    }
                    
                    Log.d(TAG, "TTS downloaded: ${ttsFile.absolutePath} (${ttsFile.length()} bytes)")
                    playAudioFile(ttsFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "TTS download error: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * Simple JSON string escape.
     */
    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    // ── Audio playback ──

    /**
     * Play an audio file through the HUD speaker.
     * Called from onStartCommand when ACTION_PLAY_AUDIO is received.
     * Runs playback on a background thread to avoid ANR.
     */
    private fun playAudioFile(path: String) {
        object : Thread("HudAudioPlayer") {
            override fun run() {
                try {
                    val mediaPlayer = MediaPlayer()
                    mediaPlayer.setDataSource(path)
                    mediaPlayer.prepare()
                    Log.d(TAG, "AudioPlayer: starting $path " +
                            "(duration=${mediaPlayer.duration}ms)")
                    mediaPlayer.start()
                    mediaPlayer.setOnCompletionListener { mp ->
                        Log.d(TAG, "AudioPlayer: completed")
                        mp.release()
                    }
                    mediaPlayer.setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "AudioPlayer error: what=$what extra=$extra")
                        mp.release()
                        true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AudioPlayer exception: ${e.message}")
                }
            }
        }.start()
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "HUD OBD",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle("HUD")
            .setContentText("Local OBD")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
