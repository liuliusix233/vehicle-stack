package com.vox.castsink

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import java.net.ServerSocket
import java.net.Socket

/**
 * 环境导出服务: TCP :27185，连上即回一段 JSON（build + 全部 display 详细参数）。
 * 供 PC 侧采集车机/仪表盘分辨率等数据，无需任何存储权限。
 */
object EnvServer {

    private const val TAG = "EnvServer"
    const val PORT = 27185
    @Volatile private var started = false
    @Volatile var clusterViewSize: String = ""

    private fun esc(s: String?): String =
        (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

    /** 读 HTTP 请求行（如 "GET /tab?set=3 HTTP/1.1"）；客户端不发请求则超时返回空串 */
    private fun readRequestLine(s: Socket): String {
        return try {
            s.soTimeout = 1200
            val inp = s.getInputStream()
            val buf = ByteArray(512)
            var n = 0
            while (n < buf.size) {
                val r = inp.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
                if (String(buf, 0, n, Charsets.US_ASCII).indexOf('\n') >= 0) break
            }
            String(buf, 0, n, Charsets.US_ASCII).substringBefore('\r').substringBefore('\n')
        } catch (_: Throwable) { "" }
    }

    /** 指令入口：GET /tab?set=N 直接切页，GET /tab?next=1 下一页 / next=0 上一页；回 {"tab":N} */
    private fun handleTab(ctx: Context, req: String): String {
        val path = req.substringAfter(' ').substringBefore(' ')
        val q = path.substringAfter('?', "")
        val parts = q.split('&')
        val set = parts.firstOrNull { it.startsWith("set=") }?.substringAfter('=')?.trim()?.toIntOrNull()
        val next = parts.firstOrNull { it.startsWith("next=") }?.substringAfter('=')?.trim()?.toIntOrNull()
        val idx = when {
            set != null -> ClusterTabs.clamp(set)
            next != null -> ClusterTabs.clamp(ClusterTabs.index + if (next == 0) -1 else 1)
            else -> ClusterTabs.index
        }
        if (idx != ClusterTabs.index) {
            ClusterTabs.index = idx
            try {
                ctx.sendBroadcast(Intent(ClusterTabs.ACTION).putExtra(ClusterTabs.EXTRA_INDEX, idx))
            } catch (t: Throwable) {
                Log.w(TAG, "tab broadcast fail: " + t.message)
            }
        }
        Log.i(TAG, "tab set -> $idx")
        return "{\"tab\":" + idx + ",\"name\":\"" + esc(ClusterTabs.NAMES[idx]) + "\"}"
    }

    fun snapshot(ctx: Context): String {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.getDisplays()
        val sb = StringBuilder(1024)
        sb.append("{\"build\":{")
        sb.append("\"manufacturer\":\"").append(esc(Build.MANUFACTURER)).append("\",")
        sb.append("\"model\":\"").append(esc(Build.MODEL)).append("\",")
        sb.append("\"device\":\"").append(esc(Build.DEVICE)).append("\",")
        sb.append("\"brand\":\"").append(esc(Build.BRAND)).append("\",")
        sb.append("\"release\":\"").append(esc(Build.VERSION.RELEASE)).append("\",")
        sb.append("\"sdk\":").append(Build.VERSION.SDK_INT).append(",")
        sb.append("\"abis\":\"").append(esc(Build.SUPPORTED_ABIS.joinToString(","))).append("\",")
        sb.append("\"fingerprint\":\"").append(esc(Build.FINGERPRINT)).append("\"")
        sb.append("},\"clusterView\":\"").append(esc(clusterViewSize)).append("\",")
        sb.append("\"tab\":").append(ClusterTabs.index).append(",")
        sb.append("\"telemetry\":{\"speed\":").append(Telemetry.speed)
        sb.append(",\"rpm\":").append(Telemetry.rpm)
        sb.append(",\"hz\":").append(Telemetry.hz)
        sb.append(",\"ageMs\":").append(System.currentTimeMillis() - Telemetry.lastAt).append("},")
        sb.append("\"displays\":[")
        for ((i, d) in displays.withIndex()) {
            if (i > 0) sb.append(",")
            val real = Point()
            d.getRealSize(real)
            val m = DisplayMetrics()
            d.getRealMetrics(m)
            val mode = d.mode
            sb.append("{\"id\":").append(d.displayId).append(",")
            sb.append("\"name\":\"").append(esc(d.name)).append("\",")
            sb.append("\"state\":").append(d.state).append(",")
            sb.append("\"rotation\":").append(d.rotation).append(",")
            sb.append("\"realSize\":[").append(real.x).append(",").append(real.y).append("],")
            sb.append("\"densityDpi\":").append(m.densityDpi).append(",")
            sb.append("\"density\":").append(m.density).append(",")
            sb.append("\"scaledDensity\":").append(m.scaledDensity).append(",")
            sb.append("\"xdpi\":").append(m.xdpi).append(",")
            sb.append("\"ydpi\":").append(m.ydpi).append(",")
            sb.append("\"mode\":{\"physicalWidth\":").append(mode.physicalWidth)
            sb.append(",\"physicalHeight\":").append(mode.physicalHeight)
            sb.append(",\"refreshRate\":").append(mode.refreshRate).append("},")
            sb.append("\"modes\":[")
            for ((j, mm) in d.supportedModes.withIndex()) {
                if (j > 0) sb.append(",")
                sb.append("[").append(mm.physicalWidth).append(",")
                    .append(mm.physicalHeight).append(",").append(mm.refreshRate).append("]")
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        Thread {
            try {
                val ss = ServerSocket(PORT)
                Log.i(TAG, "env server listening :" + PORT)
                while (true) {
                    val s: Socket = ss.accept()
                    try {
                        val req = readRequestLine(s)
                        val out = s.getOutputStream()
                        val body = if (req.substringAfter(' ').substringBefore(' ').startsWith("/tab"))
                            handleTab(ctx, req) else snapshot(ctx)
                        out.write(body.toByteArray(Charsets.UTF_8))
                        out.flush()
                        Log.i(TAG, "env response sent: " + body.take(48))
                    } catch (t: Throwable) {
                        Log.w(TAG, "send fail: " + t.message)
                    } finally {
                        try { s.close() } catch (_: Throwable) {}
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "env server stopped: " + t.message)
            }
        }.start()
    }
}
