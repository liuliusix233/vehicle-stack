package com.vox.castsink

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * 车机反向隧道：车机主动外连家里公网域名，Tailscale 不在线也能被连进来。
 *
 * 外层 WSS（经家里 Caddy，TLS）→ 中继服务；内层帧：
 *   [type:1][connId:4 big-endian][payload]
 *   type 1=OPEN 2=DATA 3=CLOSE
 * 中继每收到一个操作端连接，就向车机发 OPEN；车机连本机 127.0.0.1:8888
 * （合并后的 ShellService），双向转发字节 —— 协议层完全不用改。
 */
object RelayTunnel {
    private const val TAG = "RelayTunnel"
    private const val HOST = "nas.<PRIVATE_DOMAIN>"
    private const val PORT = 8731
    private const val PATH = "/car-tunnel"
    private const val CAR_TOKEN = "<SECRET>"
    private const val LOCAL_PORT = 8888

    @Volatile private var started = false
    @Volatile private var out: OutputStream? = null
    private val outLock = Any()
    private val conns = ConcurrentHashMap<Long, Socket>()
    private val nextId = AtomicLong(1)

    fun start(ctx: Context) {
        if (started) return
        started = true
        thread(name = "relay-tunnel") {
            var backoff = 2000L
            while (true) {
                try {
                    runOnce()
                    backoff = 2000L
                } catch (t: Throwable) {
                    Log.w(TAG, "tunnel: " + (t.message ?: t.javaClass.simpleName))
                } finally {
                    closeAll()
                }
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    return@thread
                }
                backoff = (backoff * 2).coerceAtMost(60000L)
            }
        }
    }

    private fun runOnce() {
        val socket = connectTls()
        try {
            socket.soTimeout = 90000
            val inp = socket.inputStream
            val o = socket.outputStream
            synchronized(outLock) { out = o }
            handshake(inp, o)
            Log.i(TAG, "tunnel up to $HOST:$PORT")
            readLoop(inp)
        } finally {
            synchronized(outLock) { out = null }
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun connectTls(): SSLSocket {
        return try {
            tlsSocket(strict = true)
        } catch (t: Throwable) {
            Log.w(TAG, "strict TLS failed (" + t.message + "), retry insecure")
            tlsSocket(strict = false)
        }
    }

    private fun tlsSocket(strict: Boolean): SSLSocket {
        val ctx = if (strict) {
            SSLContext.getDefault()
        } else {
            val c = SSLContext.getInstance("TLS")
            c.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
            c
        }
        val raw = ctx.socketFactory.createSocket() as SSLSocket
        raw.connect(InetSocketAddress(HOST, PORT), 15000)
        val p = raw.sslParameters
        p.serverNames = listOf(SNIHostName(HOST))
        p.endpointIdentificationAlgorithm = if (strict) "HTTPS" else null
        raw.sslParameters = p
        raw.soTimeout = 15000
        raw.startHandshake()
        return raw
    }

    private fun handshake(inp: InputStream, o: OutputStream) {
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keyB64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        val req = "GET $PATH?role=car&token=$CAR_TOKEN HTTP/1.1\r\n" +
            "Host: $HOST:$PORT\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $keyB64\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "User-Agent: CarBridge/1.0\r\n\r\n"
        o.write(req.toByteArray())
        o.flush()
        val head = ByteArrayOutputStream()
        while (head.size() < 8192) {
            val b = inp.read()
            if (b < 0) throw EOFException("handshake eof")
            head.write(b)
            val s = head.toString("ISO-8859-1")
            if (s.endsWith("\r\n\r\n")) {
                if (!s.startsWith("HTTP/1.1 101") && !s.startsWith("HTTP/1.0 101"))
                    throw IllegalStateException("ws handshake: " + s.lineSequence().firstOrNull())
                return
            }
        }
        throw IllegalStateException("ws handshake too large")
    }

    private fun readLoop(inp: InputStream) {
        while (true) {
            val b0 = inp.read()
            if (b0 < 0) throw EOFException("tunnel eof")
            val fin = (b0 and 0x80) != 0
            val op = b0 and 0x0f
            val b1 = inp.read()
            if (b1 < 0) throw EOFException("tunnel eof")
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) {
                len = ((readByte(inp).toLong() shl 8) or readByte(inp).toLong())
            } else if (len == 127L) {
                len = 0
                for (i in 0 until 8) len = (len shl 8) or readByte(inp).toLong()
            }
            if (len > 8L * 1024 * 1024) throw IllegalStateException("frame too large")
            val mask = if (masked) readN(inp, 4) else null
            val payload = readN(inp, len.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (op) {
                0x2 -> onBinary(payload)
                0x8 -> throw EOFException("ws close")
                0x9 -> writeWsFrame(0xA, payload)
                0xA -> {}
                0x1 -> {}
                else -> if (!fin) throw IllegalStateException("fragmented ws not supported")
            }
        }
    }

    private fun onBinary(payload: ByteArray) {
        if (payload.size < 5) return
        val type = payload[0].toInt() and 0xff
        val cid = ((payload[1].toLong() and 0xff) shl 24) or
            ((payload[2].toLong() and 0xff) shl 16) or
            ((payload[3].toLong() and 0xff) shl 8) or
            (payload[4].toLong() and 0xff)
        when (type) {
            1 -> openLocal(cid)
            2 -> {
                val s = conns[cid] ?: return
                val data = payload.copyOfRange(5, payload.size)
                try {
                    synchronized(s) {
                        s.getOutputStream().write(data)
                        s.getOutputStream().flush()
                    }
                } catch (t: Throwable) {
                    closeLocal(cid)
                }
            }
            3 -> closeLocal(cid)
        }
    }

    private fun openLocal(cid: Long) {
        closeLocal(cid)
        try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", LOCAL_PORT), 3000)
            conns[cid] = s
            thread(name = "relay-conn-$cid") {
                val buf = ByteArray(16384)
                try {
                    val i = s.getInputStream()
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        sendBinaryFrame(2, cid, buf.copyOf(n))
                    }
                } catch (_: Throwable) {
                } finally {
                    conns.remove(cid)
                    try { s.close() } catch (_: Throwable) {}
                    sendBinaryFrame(3, cid, ByteArray(0))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "local connect failed cid=$cid: " + t.message)
            sendBinaryFrame(3, cid, ByteArray(0))
        }
    }

    private fun closeLocal(cid: Long) {
        val s = conns.remove(cid) ?: return
        try { s.close() } catch (_: Throwable) {}
    }

    private fun closeAll() {
        for (cid in conns.keys.toList()) closeLocal(cid)
        synchronized(outLock) { out = null }
    }

    private fun writeWsFrame(op: Int, payload: ByteArray = ByteArray(0)) {
        val o = out ?: return
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or op)
        val len = payload.size
        if (len < 126) {
            header.write(0x80 or len)
        } else if (len < 65536) {
            header.write(0x80 or 126)
            header.write((len ushr 8) and 0xff)
            header.write(len and 0xff)
        } else {
            header.write(0x80 or 127)
            for (i in 7 downTo 0) header.write(((len.toLong() ushr (8 * i)) and 0xff).toInt())
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        header.write(mask)
        val body = ByteArray(len)
        for (i in 0 until len) body[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        try {
            synchronized(outLock) {
                o.write(header.toByteArray())
                o.write(body)
                o.flush()
            }
        } catch (t: Throwable) {
            try { o.close() } catch (_: Throwable) {}
        }
    }

    private fun sendBinaryFrame(type: Int, cid: Long, data: ByteArray) {
        val payload = ByteArray(5 + data.size)
        payload[0] = type.toByte()
        payload[1] = ((cid ushr 24) and 0xff).toByte()
        payload[2] = ((cid ushr 16) and 0xff).toByte()
        payload[3] = ((cid ushr 8) and 0xff).toByte()
        payload[4] = (cid and 0xff).toByte()
        System.arraycopy(data, 0, payload, 5, data.size)
        writeWsFrame(0x2, payload)
    }

    private fun readByte(inp: InputStream): Int {
        val b = inp.read()
        if (b < 0) throw EOFException("eof")
        return b
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw EOFException("eof")
            off += r
        }
        return buf
    }
}
