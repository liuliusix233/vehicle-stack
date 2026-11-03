package com.vox.castsink

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 车内 Pi 的 OBD 桥（/home/<USER>/obd_http.py，V-LINK 已配对，10Hz）。
 * 车机蓝牙 UI 只允许连手机，所以车机直连失败时走这条 LAN/Tailscale 通路。
 */
object ObdNet {

    private const val TAG = "ObdNet"
    // 优先车内 LAN 直连，Tailscale 兜底（车机 TS 偶尔掉线）
    private val HOSTS = arrayOf("http://<LAN_IP>:8077/", "http://<TS_IP>:8077/")
    @Volatile private var cur = 0
    @Volatile var viaLan = false
    private var lastProbe = 0L

    @Volatile var ok = false
    @Volatile var lastOk = 0L
    @Volatile var hz = 0f

    private var started = false

    @Volatile private var lastRaw = ""   // v113: 响应去重缓存

    fun start() {
        if (started) return
        started = true
        Thread {
            var fails = 0
            while (true) {
                if (CarBus.paused) {   // v130 急停：只 sleep 不请求
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { }
                    continue
                }
                try {
                    fetch()
                    if (fails != 0) fails = 0            // 成功立即回基准
                } catch (t: Throwable) {
                    ok = false; fails++
                    Log.d(TAG, "fetch " + t.message)
                }
                // v130：退避化 — 基准 1000ms；连续失败 2s→5s→10s（上限 10s），成功复位
                val sleepMs = if (fails == 0) 1000L else longArrayOf(2000, 5000, 10000)[minOf(fails - 1, 2)]
                try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun fetch() {
        val now = System.currentTimeMillis()
        val probeLan = now - lastProbe > 30000L
        if (probeLan) lastProbe = now
        val order = if (probeLan || cur == 0) intArrayOf(0, 1) else intArrayOf(1, 0)
        var err: Throwable? = null
        for (i in order) {
            try {
                doFetch(HOSTS[i])
                cur = i
                viaLan = (i == 0)
                return
            } catch (t: Throwable) {
                err = t
            }
        }
        throw (err ?: java.io.IOException("no bridge"))
    }

    /** 把车机粗定位推给 Pi，由 Pi 查高程（open-elevation）。ageMs=坐标年龄，旧消费端忽略未知参数不报错 */
    fun pushGeo(lat: Double, lon: Double, ageMs: Long = 0L) {
        val order = if (cur == 0) intArrayOf(0, 1) else intArrayOf(1, 0)
        for (i in order) {
            try {
                val c = URL(HOSTS[i] + "geo?lat=" + lat + "&lon=" + lon + "&age=" + ageMs).openConnection() as HttpURLConnection
                c.connectTimeout = 500
                c.readTimeout = 800
                c.requestMethod = "GET"
                c.inputStream.use { it.readBytes() }
                c.disconnect()
                return
            } catch (_: Throwable) { }
        }
    }

    private fun doFetch(urlStr: String) {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.connectTimeout = 600
        c.readTimeout = 800
        c.requestMethod = "GET"
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        // v113：响应与上次完全相同→跳过整段 JSON 解析与字段写入（无变化不处理）
        if (txt == lastRaw) { lastOk = System.currentTimeMillis(); return }
        lastRaw = txt
        val o = JSONObject(txt)
        val good = o.optBoolean("ok", false)
        val rpm = o.optDouble("rpm", -1.0).toFloat()
        val sp = o.optDouble("speed", -1.0).toFloat()
        val odo = o.optDouble("odo", -1.0).toFloat()
        val soc = o.optDouble("soc", -1.0).toFloat()
        val volt = o.optDouble("volt", -1.0).toFloat()
        val fuel = o.optDouble("fuel", -1.0).toFloat()
        val amb = o.optDouble("amb", -1.0).toFloat()
        val oil = o.optDouble("oil", -1.0).toFloat()
        val intake = o.optDouble("intake", -1.0).toFloat()
        val run = o.optDouble("run", -1.0).toFloat()
        val b2000 = o.optDouble("b2000", -1.0).toFloat()
        val psIn = o.optDouble("ps_in", -1.0).toFloat()
        val sdcIn = o.optDouble("sdc_in", -1.0).toFloat()
        val psOut = o.optDouble("ps_out", -1.0).toFloat()
        val psTotal = o.optDouble("ps_total", -1.0).toFloat()
        val psTemp = o.optDouble("ps_temp", -1.0).toFloat()
        val thr = o.optDouble("thr", -1.0).toFloat()
        val cool = o.optDouble("cool", -1.0).toFloat()
        val alt = o.optDouble("alt", -1.0).toFloat()
        val load = o.optDouble("load", -1.0).toFloat()
        if (good && rpm >= 0f) VehicleState.obdRpm = rpm
        if (good && sp >= 0f) VehicleState.obdSpeed = sp
        if (good && odo > 0f && odo < 2000000f) VehicleState.obdOdoKm = odo
        if (good && soc >= 0f && soc <= 100f) VehicleState.obdSoc = soc
        if (good && volt > 6f && volt < 20f) VehicleState.obdVolt = volt
        if (good && fuel >= 0f) VehicleState.obdFuel = fuel
        if (good && amb > -60f) VehicleState.obdAmb = amb
        if (good && oil > -60f) VehicleState.obdOil = oil
        if (good && intake > -60f) VehicleState.obdIntake = intake
        if (good && run >= 0f) VehicleState.obdRun = run
        if (b2000 >= 0f) VehicleState.ble2000 = b2000
        if (psIn >= 0f) VehicleState.psIn = psIn
        if (sdcIn >= 0f) VehicleState.sdcIn = sdcIn
        if (psOut >= 0f) VehicleState.psOut = psOut
        if (psTotal >= 0f) VehicleState.psTotalRaw = psTotal
        if (psTemp >= -40f) VehicleState.psTemp = psTemp
        if (good && thr >= 0f) VehicleState.obdThrottle = thr
        if (good && cool > -60f) VehicleState.obdCoolant = cool
        if (alt > -400f) VehicleState.obdAltM = alt   // v72：高程来自 Pi /geo，与 OBD 在否无关
        if (good && load >= 0f) VehicleState.obdLoad = load
        hz = o.optDouble("hz", 0.0).toFloat()
        ok = good
        lastOk = System.currentTimeMillis()
    }
}
