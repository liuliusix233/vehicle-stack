package com.vox.castsink

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "H264TcpReceiver"
private const val MAX_FRAME = 8 * 1024 * 1024

/**
 * TCP 接收端：4 字节大端长度前缀分帧，每帧一个 Annex-B 访问单元。
 * 借鉴：NativeWfdSink 的 WFD 源 RTP→Annex-B 分帧思路，M1 简化为长度前缀 TCP。
 * 断线重连：客户端断开后关闭 socket，继续在同一端口 accept 新连接。
 */
class H264TcpReceiver(
    private val port: Int,
    private val onFrame: (ByteArray) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var server: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ acceptLoop() }, "H264TcpReceiver").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        thread?.interrupt()
        thread = null
    }

    private fun acceptLoop() {
        while (running) {
            val socket: Socket
            try {
                var s = server
                if (s == null || s.isClosed) {
                    s = ServerSocket(port)
                    s.reuseAddress = true
                    server = s
                }
                socket = s.accept()
            } catch (e: IOException) {
                if (!running) return
                try { Thread.sleep(300) } catch (_: InterruptedException) { return }
                continue
            }
            handleSocket(socket)
        }
    }

    private fun handleSocket(socket: Socket) {
        Log.i(TAG, "client connected: ${socket.remoteSocketAddress}")
        try {
            socket.use { s ->
                val input = DataInputStream(s.getInputStream())
                val header = ByteArray(4)
                while (running) {
                    input.readFully(header)
                    val len = ((header[0].toInt() and 0xFF) shl 24) or
                        ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)
                    if (len <= 0 || len > MAX_FRAME) throw IOException("bad frame length $len")
                    val frame = ByteArray(len)
                    input.readFully(frame)
                    onFrame(frame)
                }
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "client disconnected: ${e.message}")
        }
    }
}
