package com.vox.hud

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * 车速/转速 TCP 推流服务（端口 27188）。
 * 车机主动连进来（车机侧入站被防火墙挡，只能由车机出站）。
 * 每行一个 JSON：{"speed":..,"rpm":..,"ts":..}
 * 单写线程 + 队列：accept 只换 socket，避免写入竞态。
 */
object SpeedServer {

    private const val TAG = "SpeedServer"
    private const val PORT = 27188

    private val queue = LinkedBlockingQueue<String>(8)
    @Volatile private var started = false
    @Volatile private var out: OutputStream? = null

    fun start() {
        if (started) return
        started = true
        Thread {
            try {
                val ss = ServerSocket(PORT)
                Log.i(TAG, "listening :" + PORT)
                while (true) {
                    val s = ss.accept()
                    Log.i(TAG, "client " + s.inetAddress.hostAddress)
                    try { out?.flush() } catch (_: Exception) {}
                    out = s.getOutputStream()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "server stopped: " + t.javaClass.simpleName + ": " + t.message)
                started = false
            }
        }.apply { isDaemon = true }.start()

        Thread {
            while (true) {
                val line = try { queue.take() } catch (e: Exception) { continue }
                val o = out ?: continue
                try {
                    o.write(line.toByteArray())
                    o.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "write fail: " + e.javaClass.name + ": " + e.message)
                    out = null
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun send(speed: Int, rpm: Int) {
        if (!started) start()
        val line = "{\"speed\":" + speed + ",\"rpm\":" + rpm + ",\"ts\":" + System.currentTimeMillis() + "}\n"
        if (queue.size >= 8) queue.clear()
        queue.offer(line)
    }
}
