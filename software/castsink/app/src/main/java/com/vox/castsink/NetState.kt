package com.vox.castsink

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 公网 HA 数据流（与 HUD 同源）：https://nas.<PRIVATE_DOMAIN>:8443
 * 用 /api/template 一次取齐 10 个值，避免整包 states（600KB）和 10 次串行请求。
 */
object NetState {

    private const val TAG = "NetState"
    private const val BASE = "https://nas.<PRIVATE_DOMAIN>:8443/api/template"
    private const val TOKEN = "<SECRET>"
    private const val TEMPLATE = "{{ states('sensor.vehicle_battery_total') }}|{{ states('sensor.cadillac_battery') }}|{{ states('sensor.mini_power_battery') }}|" +
        "{{ states('sensor.cadillac_charge_power') }}|{{ states('sensor.cadillac_discharge_power') }}|{{ states('sensor.cadillac_ac_discharge_w') }}|" +
        "{{ states('input_boolean.pelite_ac') }}|{{ states('sensor.xiaomi_new3pr_8b92_co2_density') }}|" +
        "{{ states('sensor.xiaomi_new3pr_8b92_temperature') }}|{{ states('sensor.vehicle_load_power') }}|" +
        "{{ states('sensor.hud_gps_altitude') }}|{{ states('sensor.hud_gps_status') }}|" +
        "{{ states('sensor.mini_power_discharge_power') }}"

    @Volatile var pack = -1f
    @Volatile var b2000 = -1f
    @Volatile var b1000 = -1f
    @Volatile var inW = -1f
    @Volatile var outW = -1f
    @Volatile var acOn = false
    @Volatile var acW = -1f
    @Volatile var co2 = -1f
    @Volatile var cabinT = -1f
    @Volatile var loadW = -1f
    @Volatile var altitude = -1000f
    @Volatile var gpsStatus = ""
    @Volatile var miniOutW = -1f
    @Volatile var ok = false
    @Volatile var lastOk = 0L
    @Volatile var err = ""

    private var started = false

    /** 数据可用：ok 一旦成功即保持，只有超过 graceMs 未更新才算失效（防闪烁） */
    fun fresh(now: Long = System.currentTimeMillis(), graceMs: Long = 15000L): Boolean =
        ok && lastOk > 0 && now - lastOk < graceMs

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
                    err = t.javaClass.simpleName; fails++
                    Log.w(TAG, "fetch: " + t.message)
                }
                // v130：400ms→1000ms 基准；失败退避 2s→5s→10s（上限 10s），成功复位
                val sleepMs = if (fails == 0) 1000L else longArrayOf(2000, 5000, 10000)[minOf(fails - 1, 2)]
                try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun fetch() {
        val conn = URL(BASE).openConnection() as HttpURLConnection
        // 车机 Android 12 对 ZeroSSL 交叉签名链判 Chain validation failed → 用钉死 USERTrust 根的校验器
        (conn as? javax.net.ssl.HttpsURLConnection)?.sslSocketFactory = HaTrust.socketFactory
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer " + TOKEN)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        val body = JSONObject().put("template", TEMPLATE).toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code != 200) { err = "HTTP " + code; conn.disconnect(); return }
        var txt = conn.inputStream.bufferedReader().use { it.readText() }.trim()
        conn.disconnect()
        if (txt.startsWith("\"")) {
            txt = try { JSONObject("{\"v\":" + txt + "}").getString("v") } catch (e: Exception) { txt.trim('"') }
        }

        val a = txt.split("|")
        if (a.size < 13) { err = "shape " + a.size; return }
        fun f(i: Int, old: Float): Float = a[i].trim().toFloatOrNull() ?: old

        pack = f(0, pack)
        b2000 = f(1, b2000)
        b1000 = f(2, b1000)
        inW = f(3, inW)
        outW = f(4, outW)
        acW = f(5, acW)
        acOn = a[6].trim() == "on"
        co2 = f(7, co2)
        cabinT = f(8, cabinT)
        loadW = f(9, loadW)
        altitude = f(10, altitude)
        gpsStatus = a[11].trim()
        miniOutW = f(12, miniOutW)

        ok = true
        lastOk = System.currentTimeMillis()
        err = ""
    }
}
