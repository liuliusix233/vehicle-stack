package com.vox.castsink

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * v130 链路降载：单例，内部持有一条常驻 Socket 到 127.0.0.1:8888（ShellService 命令口）。
 * 单写线程 + 请求队列串行化执行，替代各模块各自开 socket 的做法。
 * - 断线重连：失败指数退避 1s→2s→5s→10s（上限 10s），成功即复位
 * - 全局令牌桶：≤2 条命令/秒，超限排队丢弃最旧（不堆积）
 * - #BUSPAUSE on：急停，send() 直接返回 null，各轮询循环 sleep 不请求
 */
object CarBus {

    private const val TAG = "CarBus"
    private const val HOST = "127.0.0.1"
    private const val PORT = 8888
    private const val MAX_QUEUE = 2                       // 令牌桶容量=2（2 条/秒，超出丢最旧）
    private val BACKOFF = intArrayOf(1000, 2000, 5000, 10000)

    @Volatile var paused = false                          // #BUSPAUSE <on|off> 运行时急停
    @Volatile private var started = false
    @Volatile private var sock: Socket? = null

    private class Req(val cmd: String, val timeoutMs: Int) {
        val latch = CountDownLatch(1)
        @Volatile var result: String? = null
        val deadline: Long = System.currentTimeMillis() + timeoutMs + 500L
    }

    private val queue = LinkedBlockingQueue<Req>()

    fun start() {
        if (started) return
        started = true
        Thread {
            try { loop() } catch (t: Throwable) { Log.w(TAG, "thread die: " + t.message) }
        }.apply { isDaemon = true; name = "car-bus" }.start()
        Log.i(TAG, "started")
    }

    /** 同步风格：入队并等待响应；暂停/超时/断线返回 null。 */
    fun send(cmd: String, timeoutMs: Int): String? {
        if (paused) return null
        val r = Req(cmd, timeoutMs)
        queue.add(r)
        // 令牌桶超限：丢弃最旧请求（立即以 null 兑现），不堆积
        while (queue.size > MAX_QUEUE) {
            val old = queue.poll() ?: break
            if (old === r) { queue.add(old); break }
            old.result = null
            old.latch.countDown()
        }
        return try {
            if (!r.latch.await(timeoutMs.toLong() + 3000L, TimeUnit.MILLISECONDS)) null else r.result
        } catch (_: InterruptedException) {
            null
        }
    }

    /** 常驻 socket 获取：失败按 1s→2s→5s→10s 退避重连，成功复位。 */
    private fun ensureSocket(): Socket? {
        sock?.let { if (it.isConnected && !it.isClosed) return it }
        var idx = 0
        while (true) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(HOST, PORT), 1500)
                s.tcpNoDelay = true
                sock = s
                idx = 0                                   // 成功即复位
                return s
            } catch (_: Throwable) {
                try { Thread.sleep(BACKOFF[idx].toLong()) } catch (_: InterruptedException) { return null }
                if (idx < BACKOFF.size - 1) idx++         // 上限 10s
            }
        }
    }

    private fun drainPaused() {
        while (true) {
            val q = queue.poll() ?: return
            q.result = null
            q.latch.countDown()
        }
    }

    private fun loop() {
        var tokens = 2.0
        var lastRefill = System.currentTimeMillis()
        while (true) {
            try {
                if (paused) {                             // 急停：排空队列，只睡不发
                    drainPaused()
                    Thread.sleep(500)
                    continue
                }
                // 令牌桶 2 条/秒：按时间连续补充，容量 2
                val now = System.currentTimeMillis()
                tokens = minOf(2.0, tokens + (now - lastRefill) / 500.0)
                lastRefill = now
                if (tokens < 1.0) { Thread.sleep(200); continue }
                val r = queue.poll() ?: continue          // 无请求：只消耗时间不消耗令牌
                tokens -= 1.0
                if (System.currentTimeMillis() > r.deadline) { r.result = null; r.latch.countDown(); continue }
                val s = ensureSocket()
                if (s == null) { r.result = null; r.latch.countDown(); continue }
                try {
                    s.soTimeout = minOf(r.timeoutMs, 3000)
                    s.getOutputStream().write((r.cmd + "\n").toByteArray())
                    s.getOutputStream().flush()
                    val sb = StringBuilder()
                    val buf = ByteArray(8192)
                    while (true) {                        // 服务端每命令写完即关 socket：读到 EOF 即完整响应
                        val n = try { s.getInputStream().read(buf) } catch (_: SocketTimeoutException) { -1 }
                        if (n <= 0) break
                        sb.append(String(buf, 0, n))
                    }
                    r.result = sb.toString()
                } catch (e: Throwable) {                  // 发送失败：关 socket，下轮退避重连
                    try { s.close() } catch (_: Throwable) { }
                    sock = null
                    r.result = null
                }
                r.latch.countDown()
            } catch (t: Throwable) {
                Log.d(TAG, "loop: " + t.message)
                try { Thread.sleep(1000) } catch (_: InterruptedException) { }
            }
        }
    }
}
