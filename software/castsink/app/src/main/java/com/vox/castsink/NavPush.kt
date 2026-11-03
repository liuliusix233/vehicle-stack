package com.vox.castsink

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 车机导航 TBT -> Pi 局域网直推。
 * v130：改走 CarBus 单例常驻链路（不再自建 Socket），周期保持 2500ms，
 * 新增新鲜度闸门：query 往返 >1500ms 的结果直接丢弃不推。
 */
object NavPush {

    private const val TAG = "NavPush"
    private const val CMD = "#BINDSVC com.sgm.navi.hmi com.sgm.navi.exportservice.MapService 4 " +
            "s:com.vox.castsink s:NAVI_AUTO_API -- 29 s:com.vox.castsink"
    private val HOSTS = arrayOf("http://<LAN_IP>:8899/nav", "http://<TS_IP>:8899/nav")

    @Volatile private var started = false
    @Volatile var lastPush = 0L
    @Volatile var lastJson = ""
    @Volatile var ok = false

    fun start() {
        if (started) return
        started = true
        CarBus.start()
        Thread {
            while (true) {
                try {
                    val t0 = System.currentTimeMillis()
                    val raw = CarBus.send(CMD, 1500)
                    val cost = System.currentTimeMillis() - t0
                    // v130：新鲜度闸门 — 一次 query 往返 >1500ms 的结果直接丢弃不推（记日志）
                    val j = if (raw == null) null
                            else if (cost > 1500) { Log.i(TAG, "stale: cost=${cost}ms >1500ms, drop"); null }
                            else extract(raw)
                    if (j != null) {
                        val payload = withGps(j)
                        // v113：payload 与上次相同不推（TBT/转弯去重）；单线程 while 循环天然防重入
                        if (payload != lastJson) {
                            lastJson = payload
                            ok = post(payload)
                            if (ok) lastPush = System.currentTimeMillis()
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "loop: " + t.message)
                }
                // v113：轮询周期 1s→2.5s（v130 保持 2500ms）
                try { Thread.sleep(2500) } catch (_: InterruptedException) { }
            }
        }.apply { isDaemon = true; name = "nav-push" }.start()
        Log.i(TAG, "started")
    }

    /** 把当前位置并进 TBT JSON（v113：改读 VehicleState 内存坐标，不再每秒开 #GPS socket） */
    private fun withGps(tbt: String): String {
        return try {
            val o = JSONObject(tbt)
            val lat = VehicleState.lastLat
            val lon = VehicleState.lastLon
            if (lat != 0.0 && lon != 0.0) {
                o.put("lat", lat)
                o.put("lon", lon)
                HudBus.carLat = lat
                HudBus.carLon = lon
            }
            o.toString()
        } catch (t: Throwable) {
            tbt
        }
    }

    private fun extract(txt: String): String? {
        val i = txt.indexOf("-> String: ")
        if (i < 0) return null
        val rest = txt.substring(i + "-> String: ".length)
        val open = rest.indexOf('{')
        val close = rest.lastIndexOf('}')
        if (open < 0 || close <= open) return null
        val s = rest.substring(open, close + 1)
        return try { JSONObject(s); s } catch (e: Exception) { null }
    }

    private fun post(json: String): Boolean {
        for (h in HOSTS) {
            try {
                val c = URL(h).openConnection() as HttpURLConnection
                c.connectTimeout = 700
                c.readTimeout = 900
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
                val code = c.responseCode
                try { c.inputStream.close() } catch (_: Throwable) { }
                c.disconnect()
                if (code == 200) return true
            } catch (_: Throwable) { }
        }
        return false
    }
}
