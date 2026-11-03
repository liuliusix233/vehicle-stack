package com.vox.hud

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 车速/转速 UDP 广播（低延迟、免 IP 配置、容忍双端 IP 漂移）。
 * 目标端口 27186，载荷 {"speed":km/h,"rpm":rpm,"ts":ms}
 * 广播地址：255.255.255.255 + 常见车载网段广播 <LAN_IP>
 */
object SpeedBroadcaster {

    private const val TAG = "SpeedBroadcaster"
    private const val PORT = 27186
    private const val MIN_INTERVAL_MS = 40L  // 最多 25Hz
    // 车机 / 广播（AP 隔离时靠单播）
    private val targets = arrayOf("<LAN_IP>", "255.255.255.255", "<LAN_IP>")

    private var socket: DatagramSocket? = null
    private var lastSent = 0L
    private var failLogged = false
    private var lastLog = 0L

    @Synchronized
    fun send(speedKmh: Int, rpm: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSent < MIN_INTERVAL_MS) return
        lastSent = now
        try {
            var s = socket
            if (s == null) {
                s = DatagramSocket()
                s.broadcast = true
                socket = s
            }
            val payload = "{\"speed\":$speedKmh,\"rpm\":$rpm,\"ts\":$now}".toByteArray()
            for (t in targets) {
                try {
                    s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(t), PORT))
                } catch (_: Exception) {
                }
            }
            failLogged = false
            if (now - lastLog >= 1000) {
                lastLog = now
                Log.i(TAG, "sent speed=" + speedKmh + " rpm=" + rpm)
            }
        } catch (e: Exception) {
            if (!failLogged) {
                Log.w(TAG, "broadcast failed: " + e.message)
                failLogged = true
            }
            socket = null
        }
    }
}
