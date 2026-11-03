package com.vox.castsink

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 车速/转速接收：
 *  1) TCP :27188 —— 车机主动连 HUD（车机入站被封，出站正常）。
 *     连不上就并行扫本机 /24 找 HUD，IP 漂移后自动重扫重连。
 *  2) UDP :27186 —— 广播兜底（AP 允许广播时可用）。
 */
object Telemetry {

    private const val TAG = "Telemetry"
    const val UDP_PORT = 27186
    const val TCP_PORT = 27188
    private const val PI_TAIL = "http://<TS_IP>:8080/obd"
    private const val PI_LAN = "http://<LAN_IP>:8080/obd"

    @Volatile var speed = 0f
    @Volatile var rpm = 0f
    @Volatile var lastAt = 0L
    @Volatile var hz = 0f
    @Volatile var peer = ""
    @Volatile var src = ""
    @Volatile var coolant = -1f
    @Volatile var odo = -1f
    @Volatile var engineLoad = -1f
    @Volatile var throttle = -1f
    @Volatile var ambient = -1f
    @Volatile var fuel = -1f
    @Volatile private var running = false

    private var rateCount = 0
    private var rateMark = 0L

    fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now - lastAt in 1..6000

    fun start() {
        if (running) return
        running = true
        rateMark = System.currentTimeMillis()
        startUdp()
        Thread { httpLoop() }.apply { isDaemon = true }.start()
        Thread { tcpLoop() }.apply { isDaemon = true }.start()
    }

    /** 固定 Tailscale/LAN 地址轮询 Pi OBD 代理：不受 HUD IP 漂移影响。 */
    private fun httpLoop() {
        val urls = arrayOf(PI_LAN, PI_TAIL)
        var idx = 0
        var fails = 0
        while (running) {
            if (CarBus.paused) {   // v130 急停：只 sleep 不请求
                try { Thread.sleep(2000) } catch (_: InterruptedException) { }
                continue
            }
            val u = urls[idx]
            try {
                fetch(u)
                idx = 0
                if (fails != 0) fails = 0                // 成功立即回基准
            } catch (t: Throwable) {
                idx = (idx + 1) % urls.size
                fails++
            }
            if (System.currentTimeMillis() - lastAt > 5000L) {
                speed = -1f
                rpm = -1f
                src = ""
            }
            // v130：250ms→2000ms 基准；失败退避 2s→5s→10s（上限 10s），成功复位
            val sleepMs = if (fails == 0) 2000L else longArrayOf(2000, 5000, 10000)[minOf(fails - 1, 2)]
            try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { }
        }
    }

    private fun fetch(url: String) {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 900
        c.readTimeout = 900
        c.requestMethod = "GET"
        val code = c.responseCode
        if (code != 200) { c.disconnect(); throw java.io.IOException("HTTP " + code) }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(body)
        speed = o.optDouble("speed", -1.0).toFloat()
        rpm = o.optDouble("rpm", -1.0).toFloat()
        coolant = o.optDouble("coolantTemp", -1.0).toFloat()
        odo = o.optDouble("odo", -1.0).toFloat()
        engineLoad = o.optDouble("engineLoad", -1.0).toFloat()
        throttle = o.optDouble("throttlePos", -1.0).toFloat()
        ambient = o.optDouble("ambientTemp", -1.0).toFloat()
        fuel = if (o.has("fuelLevelHA")) o.optDouble("fuelLevelHA", -1.0).toFloat()
               else o.optDouble("fuelLevel", -1.0).toFloat()
        val now = System.currentTimeMillis()
        lastAt = now
        src = "PI"
        rateCount++
        if (now - rateMark >= 1000) {
            hz = rateCount * 1000f / (now - rateMark).coerceAtLeast(1)
            rateCount = 0
            rateMark = now
        }
    }

    private fun startUdp() {
        Thread {
            try {
                val sock = DatagramSocket(UDP_PORT)
                sock.broadcast = true
                val buf = ByteArray(512)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    parse(String(p.data, 0, p.length))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "udp stopped: " + t.message)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 收集候选 /24 网段：优先 wlan/swlan 网卡，其次其它（车机有 vt101 等虚拟网卡，必须先挑 WiFi）。 */
    private fun localPrefixes(): List<String> {
        val wifi = ArrayList<String>()
        val other = ArrayList<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name ?: ""
                val isWifi = name.startsWith("wlan") || name.startsWith("swlan")
                for (a in nif.inetAddresses) {
                    val h = a.hostAddress ?: continue
                    if (!(h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("172."))) continue
                    val p = h.split(".")
                    if (p.size != 4) continue
                    val prefix = p[0] + "." + p[1] + "." + p[2] + "."
                    if (isWifi) { if (!wifi.contains(prefix)) wifi.add(prefix) }
                    else { if (!other.contains(prefix)) other.add(prefix) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "iface: " + e.message)
        }
        return wifi + other
    }

    private fun findServer(prefix: String): String? {
        val pool = Executors.newFixedThreadPool(32)
        try {
            val tasks = (2..254).map { i ->
                Callable {
                    val ip = prefix + i
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress(ip, TCP_PORT), 180) }
                        ip
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            val futures = pool.invokeAll(tasks, 6, TimeUnit.SECONDS)
            for (f in futures) {
                try {
                    val ip = f.get()
                    if (ip != null) return ip
                } catch (_: Exception) {
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return null
    }

    private fun tcpLoop() {
        while (running) {
            if (isFresh()) { try { Thread.sleep(4000) } catch (_: InterruptedException) { }; continue }
            val prefixes = localPrefixes()
            if (prefixes.isEmpty()) {
                Thread.sleep(3000)
                continue
            }
            var ip: String? = null
            for (prefix in prefixes) {
                ip = findServer(prefix)
                if (ip != null) break
            }
            if (ip == null) {
                Log.i(TAG, "no server on " + prefixes.joinToString(","))
                Thread.sleep(3000)
                continue
            }
            peer = ip
            Log.i(TAG, "connected " + ip)
            try {
                Socket().use { s ->
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(ip, TCP_PORT), 1500)
                    val br = BufferedReader(InputStreamReader(s.getInputStream()))
                    while (running) {
                        val line = br.readLine() ?: break
                        parse(line)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "tcp drop (" + ip + "): " + e.message)
            }
            peer = ""
            Thread.sleep(800)
        }
    }

    private fun parse(text: String) {
        try {
            val o = JSONObject(text)
            speed = o.optDouble("speed", -1.0).toFloat()
            rpm = o.optDouble("rpm", -1.0).toFloat()
            val now = System.currentTimeMillis()
            lastAt = now
            src = "HUD"
            rateCount++
            if (now - rateMark >= 1000) {
                hz = rateCount * 1000f / (now - rateMark).coerceAtLeast(1)
                rateCount = 0
                rateMark = now
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad packet: " + text.take(40))
        }
    }
}
