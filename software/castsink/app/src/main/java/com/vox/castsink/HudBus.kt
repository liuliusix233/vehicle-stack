package com.vox.castsink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import org.json.JSONObject

/**
 * 收车机上 HUD app（com.vox.hud）的广播：com.vox.hud.UPDATE / extra "json"。
 * HUD 自己读 OBD，CastSink 只要听广播就能拿转速/车速，绕开蓝牙配对与权限。
 */
object HudBus {

    private const val TAG = "HudBus"
    private const val ACTION = "com.vox.hud.UPDATE"

    @Volatile var rpm = -1f
    @Volatile var speed = -1f
    @Volatile var gear = ""
    @Volatile var ts = 0L
    @Volatile var seen = false
    @Volatile var lastJsonKeys = ""

    // 导航信号灯：保留完整相位表，按时间推算，避免"推一次就断"
    class Phase(val s: Long, val e: Long, val c: String)

    @Volatile var phases: List<Phase> = emptyList()
    @Volatile var lightTs = 0L
    @Volatile var lightLat = Double.NaN
    @Volatile var lightLon = Double.NaN
    @Volatile var carLat = Double.NaN
    @Volatile var carLon = Double.NaN

    private fun distM(): Double {
        if (carLat.isNaN() || lightLat.isNaN()) return 0.0
        val dLat = (lightLat - carLat) * 111320.0
        val dLon = (lightLon - carLon) * 111320.0 * kotlin.math.cos(Math.toRadians(carLat))
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    /** 返回 (颜色, 剩余秒)；无有效相位返回 ("", -1) */
    fun lightNow(): Pair<String, Int> {
        val now = System.currentTimeMillis() / 1000L
        // 只按刷新新鲜度判断：车机停止推送（离开路口/没灯）60 秒后自动消失
        if (lightTs > 0 && now - lightTs > 30) return "" to -1
        for (p in phases) {
            if (now >= p.s && now < p.e) {
                val left = (p.e - now).toInt()
                if (left in 0..180) return p.c to left
            }
        }
        return "" to -1
    }

    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try {
                    val j = i.getStringExtra("json") ?: return
                    val o = JSONObject(j)
                    val sp = o.optDouble("speed", -1.0).toFloat()
                    val rp = o.optDouble("rpm", -1.0).toFloat()
                    if (sp >= 0f) speed = sp
                    if (rp >= 0f) rpm = rp
                    if (o.has("gear")) gear = o.optString("gear", "")
                    ts = System.currentTimeMillis()
                    seen = true
                } catch (t: Throwable) {
                    Log.w(TAG, "bad json " + t.message)
                }
            }
        }
        try {
            ctx.registerReceiver(rx, IntentFilter(ACTION))
            Log.i(TAG, "receiver registered")
        } catch (t: Throwable) {
            Log.w(TAG, "register failed " + t.message)
        }
    }

    fun fresh(now: Long = System.currentTimeMillis()): Boolean = seen && now - ts < 8000L
}
