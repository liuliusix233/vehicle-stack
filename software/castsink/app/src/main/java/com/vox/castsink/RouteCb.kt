package com.vox.castsink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * v87 探针：注册 INaviAutoRouteCallback，把回调原文回传 Pi /debug。
 * 事务码来源：nav3.txt 反编译 INaviAutoApiBinder$Stub$Proxy.addNaviAutoRouteCallback -> transact(7)
 */
object RouteCb {
    private const val TAG = "RouteCb"
    private const val PKG = "com.sgm.navi.hmi"
    private const val CLS = "com.sgm.navi.exportservice.MapService"
    private const val POOL_DESC = "com.sgm.navi.mapservice.IBinderPool"
    private const val DESC = "com.sgm.navi.mapservice.common.INaviAutoApiBinder"
    private const val CLIENT = "com.vox.castsink"
    private const val CODE_ROUTE_CB = 7
    private val HOSTS = arrayOf("http://<LAN_IP>:8899/debug", "http://<TS_IP>:8899/debug")

    @Volatile private var started = false

    private const val CODE_LIGHT_CB = 3
    private const val DESC_LIGHT = "com.sgm.navi.mapservice.common.INaviAutoCountDownLightCallback"

    private fun makeCb(src: String) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                post("{\"src\":\"" + src + "\",\"code\":" + code + ",\"raw\":\"" + esc(dump(data)) + "\"}")
            } catch (t: Throwable) {
                Log.d(TAG, src + " " + t.message)
            }
            return true
        }
    }

    private val lightCb = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                val txt = parcelText(data)
                post("{\"src\":\"light_cb\",\"code\":" + code + ",\"raw\":\"" + esc(txt) + "\"}")
                parseLight(txt)
            } catch (t: Throwable) {
                Log.d(TAG, "light " + t.message)
            }
            return true
        }
    }

    /** Parcel 里是 UTF-16LE 文本：按 int 读，低 16 位先出 */
    private fun parcelText(p: Parcel): String {
        val sb = StringBuilder()
        try {
            p.setDataPosition(0)
            while (p.dataPosition() + 4 <= p.dataSize()) {
                val v = p.readInt()
                val c1 = (v and 0xFFFF).toChar()
                val c2 = ((v ushr 16) and 0xFFFF).toChar()
                if (c1 != '\u0000') sb.append(c1)
                if (c2 != '\u0000') sb.append(c2)
            }
        } catch (t: Throwable) { }
        return sb.toString()
    }

    private fun parseLight(txt: String) {
        try {
            val i = txt.indexOf('[')
            if (i < 0) return
            val arr = org.json.JSONArray(txt.substring(i))
            if (arr.length() == 0) return
            val li = arr.optJSONObject(0)?.optJSONObject("mLightInfo") ?: return
            val states = li.optJSONArray("mLightStates") ?: return
            // 整表保留：红/绿/黄连续相位都记下来，之后按时间自行推算
            val list = ArrayList<HudBus.Phase>()
            for (k in 0 until states.length()) {
                val st = states.optJSONObject(k) ?: continue
                val s = st.optLong("mStime", 0)
                val e = st.optLong("mEtime", 0)
                val c = when (st.optInt("mLightType", 0)) {
                    2 -> "red"; 3 -> "green"; 4 -> "yellow"; else -> ""
                }
                if (s > 0 && e > s && c.isNotEmpty()) list.add(HudBus.Phase(s, e, c))
            }
            list.sortBy { it.s }
            val pos = arr.optJSONObject(0)?.optJSONObject("mPosition")
            if (pos != null) {
                HudBus.lightLat = pos.optDouble("mLat", Double.NaN)
                HudBus.lightLon = pos.optDouble("mLon", Double.NaN)
            }
            HudBus.phases = list
            HudBus.lightTs = System.currentTimeMillis() / 1000L
        } catch (t: Throwable) {
            Log.d(TAG, "parseLight " + t.message)
        }
    }

    private val cb = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                val body = "{\"src\":\"route_cb\",\"code\":" + code + ",\"raw\":\"" + esc(dump(data)) + "\"}"
                post(body)
            } catch (t: Throwable) {
                Log.d(TAG, "cb " + t.message)
            }
            return true
        }
    }

    private fun dump(p: Parcel): String {
        val out = StringBuilder()
        try {
            p.setDataPosition(0)
            val desc = try { p.readString() } catch (e: Throwable) { null }
            out.append("desc=").append(desc).append(";")
            val start = p.dataPosition()
            val size = p.dataSize()
            val sb = StringBuilder()
            var i = start
            var n = 0
            while (i < size && n < 3000) {
                val v = try { p.readInt() } catch (e2: Throwable) { break }
                sb.append(String.format("%08x", v))
                i += 4
                n++
            }
            out.append("hex=").append(sb)
            out.append(";size=").append(size)
        } catch (t: Throwable) {
            out.append("dumpfail:").append(t.message)
        }
        return out.toString().take(6000)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    private fun post(body: String) {
        val bytes = body.toByteArray()
        for (h in HOSTS) {
            try {
                val c = URL(h).openConnection() as HttpURLConnection
                c.connectTimeout = 900
                c.readTimeout = 900
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(bytes) }
                c.responseCode
                try { c.inputStream.close() } catch (_: Throwable) { }
                c.disconnect()
                return
            } catch (_: Throwable) { }
        }
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        try {
            val intent = Intent().setClassName(PKG, CLS)
            ctx.applicationContext.bindService(intent, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    try {
                        val d = Parcel.obtain(); val r = Parcel.obtain()
                        d.writeInterfaceToken(POOL_DESC)
                        d.writeString(CLIENT)
                        d.writeString("NAVI_AUTO_API")
                        service.transact(4, d, r, 0)
                        r.readException()
                        val sub = r.readStrongBinder()
                        d.recycle(); r.recycle()
                        if (sub == null) {
                            post("{\"src\":\"route_cb\",\"err\":\"null sub binder\"}")
                            return
                        }
                        val d2 = Parcel.obtain(); val r2 = Parcel.obtain()
                        d2.writeInterfaceToken(DESC)
                        d2.writeString(CLIENT)
                        d2.writeStrongBinder(cb)
                        val ok = sub.transact(CODE_ROUTE_CB, d2, r2, 0)
                        d2.recycle(); r2.recycle()
                        Log.i(TAG, "route cb registered=" + ok)
                        post("{\"src\":\"route_cb\",\"registered\":" + ok + "}")
                        try {
                            val d3 = Parcel.obtain(); val r3 = Parcel.obtain()
                            d3.writeInterfaceToken(DESC)
                            d3.writeString(CLIENT)
                            d3.writeStrongBinder(lightCb)
                            val ok2 = sub.transact(CODE_LIGHT_CB, d3, r3, 0)
                            d3.recycle(); r3.recycle()
                            post("{\"src\":\"light_cb\",\"registered\":" + ok2 + "}")
                        } catch (t: Throwable) {
                            post("{\"src\":\"light_cb\",\"err\":\"" + esc(t.toString()) + "\"}")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "reg " + t.message)
                        post("{\"src\":\"route_cb\",\"err\":\"" + esc(t.toString()) + "\"}")
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) { }
            }, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.w(TAG, "bind " + t.message)
        }
    }
}
