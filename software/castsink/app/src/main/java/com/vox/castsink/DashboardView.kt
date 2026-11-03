package com.vox.castsink

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * VoxCluster / 05 —— 赛车式顶部转速条 + 中置发动机 + 两侧数据。
 * 背景为缓慢漂移的深色光晕（动态但不干扰读数）。
 */
class DashboardView(ctx: Context) : View(ctx) {

    private companion object {
        val BG = 0xFF06070A.toInt()
        val INK = 0xFFF2EEE3.toInt()
        val MUTED = 0xFF8A8478.toInt()
        val RULE = 0xFF2E2B26.toInt()
        val TRACK = 0xFF20242B.toInt()
        val RED = 0xFFE23A2E.toInt()
        val YELLOW = 0xFFF2B705.toInt()
        val BLUE = 0xFF2F6BDB.toInt()
        val GREEN = 0xFF3E8E5A.toInt()
        val AMBER = 0xFFF07A28.toInt()

        const val DW = 3904f
        const val DH = 1320f

        const val EX = 1240f
        const val EW = 1424f
        const val LX = 546f
        const val LW = 660f
        const val RX = 2704f
        const val RW = 660f
        const val STRIP_X = 546f
        const val STRIP_W = 2812f
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)
    private var s = 1f
    private var ox = 0f
    private var oy = 0f
    private var fSans: Typeface = Typeface.DEFAULT_BOLD
    private var noise: android.graphics.Bitmap? = null
    private var bgBmp: android.graphics.Bitmap? = null

    private var lastLiveAt = 0L
    private val df = SimpleDateFormat("EEE dd MMM", Locale.ENGLISH)
    private var clock = ""
    private var dateLine = ""
    private var lastClockSec = -1L

    init {
        setBackgroundColor(0x00000000)
        try { fSans = Typeface.createFromAsset(ctx.assets, "fonts/MiSans-Bold-Latin.ttf") } catch (e: Throwable) { Log.w("Dash", "font: " + e.message) }
        noise = makeNoise()
        try {
            bgBmp = ctx.assets.open("bg_cluster.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) { Log.w("Dash", "bg: " + e.message) }
        VehicleState.start(ctx)
        NetState.start()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        s = minOf(w / DW, h / DH)
        ox = (w - DW * s) / 2f
        oy = (h - DH * s) / 2f
    }

    private fun x(v: Float) = ox + v * s
    private fun y(v: Float) = oy + (v + 100f) * s
    private fun d(v: Float) = v * s

    private fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or ((a and 0xFF) shl 24)

    override fun onDraw(canvas: Canvas) {
        if (width <= 0) return
        val now = System.currentTimeMillis()
        VehicleState.update(now)
        val st = VehicleState
        val net = NetState

        // 熄火锁车：连续 90s 既无车况也无 HA 数据 → 整屏熄灭（不残留画面）
        if (st.speed >= 0f || net.fresh(now)) lastLiveAt = now
        if (lastLiveAt > 0L && now - lastLiveAt > 600000L) {
            p.color = 0xFF000000.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            postInvalidateDelayed(1000)
            return
        }

        background(canvas, now)
        topBar(canvas, net, now)
        engineBlock(canvas, st)
        gearboxBlock(canvas, st)
        rightBlock(canvas, net, st)

        postInvalidateDelayed(33)
    }

    // ------------------------------------------------------------ background

    private fun background(c: Canvas, now: Long) {
        // 底色对齐车机壁纸（#0C2039 系），多段渐变 + 抖动抑制 banding
        p.style = Paint.Style.FILL
        p.isDither = true
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xFF03080F.toInt(), 0xFF03060C.toInt(), 0xFF02040A.toInt(),
                0xFF010308.toInt(), 0xFF000104.toInt()),
            floatArrayOf(0f, 0.28f, 0.52f, 0.76f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null

        p.shader = RadialGradient(x(DW / 2f), y(120f), d(2100f),
            intArrayOf(alpha(0x1662E0, 7), alpha(0x1662E0, 0)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null

        // 细噪点做 dither，压掉面板/渐变的分层
        noise?.let { n: android.graphics.Bitmap ->
            val np = Paint(Paint.FILTER_BITMAP_FLAG)
            np.shader = android.graphics.BitmapShader(n, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), np)
        }

        // v53：整体再压暗一档（黑纱 26/255），进一步拉开读数对比
        p.shader = null
        p.color = 0x1A000000
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

    }

    private fun fmtEta(min: Float): String {
        val m = min.toInt().coerceAtLeast(1)
        return if (m >= 60) String.format(Locale.US, "%dh %02dm", m / 60, m % 60)
        else String.format(Locale.US, "%dm", m)
    }

    private fun makeNoise(): android.graphics.Bitmap {
        val n = 128
        val bm = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(7)
        val px = IntArray(n * n)
        for (i in px.indices) {
            val a = rnd.nextInt(9)
            px[i] = (a shl 24) or 0x00FFFFFF
        }
        bm.setPixels(px, 0, n, 0, 0, n, n)
        return bm
    }

    // ------------------------------------------------------------ top

    private fun topBar(c: Canvas, net: NetState, now: Long) {
        // 连接状态（占原 VOX/06 位，全部左上角）
        val live = Telemetry.isFresh(now)
        val haOk = net.ok && now - net.lastOk < 15000L
        val haStale = net.ok && !haOk
        val can = VehicleState.canCount

        fun chip(cy: Float, on: Boolean, label: String, warn: Boolean = false) {
            p.color = if (on) GREEN else if (warn) YELLOW else RULE
            c.drawCircle(x(STRIP_X), y(cy - 8f), d(6f), p)
            text(c, label, STRIP_X + 16f, cy, 20f,
                if (on) INK else if (warn) YELLOW else MUTED, Paint.Align.LEFT, 0.10f)
        }
        chip(70f, live, if (live) "OBD LIVE" else "OBD NO LINK", !live)
        chip(104f, haOk, if (haOk) "HA LINK" else if (haStale) "HA STALE" else "HA OFFLINE", haStale)
        val relay = if (ObdNet.ok) (if (ObdNet.viaLan) "PI LAN" else "PI TS") else null
        chip(138f, ObdBt.connected || relay != null,
            if (ObdBt.connected) "OBD " + ObdBt.status else (relay ?: ("OBD " + ObdBt.status)),
            !(ObdBt.connected || relay != null))
        chip(172f, can > 0, "CAN " + can)

        updateClock(now)
        text(c, clock, STRIP_X + STRIP_W, 104f, 48f, INK, Paint.Align.RIGHT)
        text(c, dateLine, STRIP_X + STRIP_W, 128f, 18f, MUTED, Paint.Align.RIGHT, 0.10f)
    }

    // ------------------------------------------------------------ rpm strip

    private fun rpmStrip(c: Canvas, st: VehicleState) {
        val sx0 = 1302f
        val sw = 1300f
        val segN = 30
        val gap = 5f
        val segW = (sw - (segN - 1) * gap) / segN
        val has = st.dispRpm >= 0f
        val lit = if (!has) 0 else (st.dispRpm / 8000f * segN).toInt().coerceIn(0, segN)

        if (has) {
            for (i in 0 until segN) {
                val sx = sx0 + i * (segW + gap)
                p.color = when {
                    i >= lit -> TRACK
                    i >= 26 -> RED
                    i >= 21 -> YELLOW
                    else -> INK
                }
                c.drawRect(x(sx), y(146f), x(sx + segW), y(196f), p)
            }
        } else {
            p.color = RULE
            c.drawRect(x(sx0), y(170f), x(sx0 + sw), y(172f), p)
        }

        text(c, "RPM", sx0 - 70f, 178f, 22f, MUTED, Paint.Align.LEFT, 0.16f)
        text(c, if (has) String.format(Locale.US, "%.1f", st.rpm / 1000f) else "NO DATA",
            sx0 + sw + 24f, 180f, 26f, if (has) INK else MUTED, Paint.Align.LEFT, 0.04f)
    }

    // ------------------------------------------------------------ center

    private fun engineBlock(c: Canvas, st: VehicleState) {
        header(c, EX, EW, "ENGINE", RED, 272f)

        // RPM 条占原 RPM/x1000 标签位，转速数值在条末
        text(c, "RPM x1000", EX, 326f, 20f, MUTED, Paint.Align.LEFT, 0.14f)
        run {
            val bx = EX
            val bw = EW - 330f
            val segN = 40
            val gap = 4f
            val segW = (bw - (segN - 1) * gap) / segN
            val lit = if (st.dispRpm < 0f) 0 else (st.dispRpm / 8000f * segN).toInt().coerceIn(0, segN)
            for (i in 0 until segN) {
                val sx = bx + i * (segW + gap)
                p.color = when {
                    i >= lit -> TRACK
                    i >= 34 -> RED
                    i >= 28 -> YELLOW
                    else -> INK
                }
                c.drawRect(x(sx), y(340f), x(sx + segW), y(400f), p)
            }
        }
        text(c, if (st.dispRpm < 0f) "--" else String.format(Locale.US, "%.1f", st.dispRpm / 1000f),
            EX + EW, 400f, 112f, if (st.dispRpm < 0f) MUTED else INK, Paint.Align.RIGHT, 0f)

        // 48V 混动输入/输出条（中心零点：左放电 / 右充电），风格同转速条
        run {
            val bx = EX
            val bw = EW - 330f
            val segN = 40
            val gap = 4f
            val segW = (bw - (segN - 1) * gap) / segN
            val pw = st.hybPowerW
            val has = pw > -9000f
            val frac = if (!has) 0f else (pw / 4000f).coerceIn(-1f, 1f)   // v60：量程 ±4kW 满格
            val mid = segN / 2
            val litCount = (kotlin.math.abs(frac) * mid).toInt()
            for (i in 0 until segN) {
                val sx = bx + i * (segW + gap)
                val lit = has && if (frac >= 0f) (i >= mid && i < mid + litCount)
                else (i < mid && i >= mid - litCount)
                p.color = when {
                    !lit -> TRACK
                    i >= mid -> GREEN
                    else -> AMBER
                }
                c.drawRect(x(sx), y(424f), x(sx + segW), y(456f), p)
            }
            text(c, "OUT", bx, 488f, 18f, MUTED, Paint.Align.LEFT, 0.12f)
            text(c, "IN", bx + bw, 488f, 18f, MUTED, Paint.Align.RIGHT, 0.12f)
            text(c,
                if (!has) "HYB --" else if (kotlin.math.abs(pw) < 1f) "HYB 0 W"
                else String.format(Locale.US, "HYB %+.0f W", pw),
                EX + EW, 488f, 24f,
                if (!has) MUTED else if (pw >= 0f) GREEN else AMBER, Paint.Align.RIGHT, 0.02f)
        }

        // POWER 行 + 档位（屏幕正中）
        text(c, "POWER", EX, 660f, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        val wp = metric(c, st.dispPower, EX, 800f, 150f, "%.0f")
        text(c, "kW", EX + wp + 18f, 800f, 40f, MUTED, Paint.Align.LEFT, 0.04f)


        text(c, "GEAR", DW / 2f, 676f, 24f, MUTED, Paint.Align.CENTER, 0.20f)
        text(c, if (st.gear == "—") "--" else st.gear, DW / 2f, 806f, 150f,
            if (st.gear == "—") MUTED else INK, Paint.Align.CENTER, 0f)

        // 右侧 COOLANT / LOAD 与 POWER 行齐平
        text(c, "COOLANT", EX + EW - 330f, 730f, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        text(c, if (st.coolant < 0f) "--" else String.format(Locale.US, "%.0f C", st.coolant),
            EX + EW, 730f, 44f, if (st.coolant > 105f) RED else if (st.coolant < 0f) MUTED else INK, Paint.Align.RIGHT, 0.02f)
        text(c, "ENG. LOAD", EX + EW - 330f, 800f, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        text(c, if (st.engineLoad < 0f) "--" else String.format(Locale.US, "%.0f %%", st.engineLoad),
            EX + EW, 800f, 44f, if (st.engineLoad < 0f) MUTED else INK, Paint.Align.RIGHT, 0.02f)
    }

    // ------------------------------------------------------------ left

    private fun gearboxBlock(c: Canvas, st: VehicleState) {
        header(c, LX, LW, "TERRAIN", INK, 300f)

        text(c, "ALTITUDE", LX, 356f, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        val alt = st.altM
        val aw = if (alt < -900f) number(c, "--", LX, 470f, 64f, MUTED)
        else number(c, String.format(Locale.US, "%.0f", alt), LX, 470f, 96f, INK)
        text(c, "m", LX + aw + 12f, 470f, 30f, MUTED, Paint.Align.LEFT, 0.04f)

        // 信号灯：高度表右侧、坡度上方（灯在右，秒数在左，分开不叠）
        run {
            val (lc, lsec) = HudBus.lightNow()
            if (lsec >= 0) {                      // 无数据 = 完全不画
                val col = when (lc) {
                    "red" -> RED
                    "green" -> GREEN
                    "yellow" -> AMBER
                    else -> MUTED
                }
                // 球径 50 = 转速条厚度；位置：高度数字右侧、坡度上方
                val r = 25f
                val cx = 980f
                val cy = 470f
                p.style = Paint.Style.FILL
                p.alpha = 255
                p.color = col
                c.drawCircle(cx, cy, r, p)
                // 数字放球正左侧，用字体实际边界做垂直居中
                val lbl = "$lsec"
                val tp = Paint(p)
                tp.color = col
                tp.textSize = 64f
                tp.textAlign = Paint.Align.RIGHT
                tp.typeface = p.typeface
                val b = android.graphics.Rect()
                tp.getTextBounds(lbl, 0, lbl.length, b)
                val by = cy - (b.top + b.bottom) / 2f
                c.drawText(lbl, cx - r - 12f, by, tp)
            }
        }

        text(c, "SLOPE", LX, 546f, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        val sl = st.slopePct
        val flat = sl < -900f || kotlin.math.abs(sl) < 0.8f
        val arrow = if (flat) "→" else if (sl > 0f) "↗" else "↘"
        val slc = if (flat) MUTED else if (sl > 0f) AMBER else BLUE
        text(c, arrow + " " + (if (sl < -900f) "--" else String.format(Locale.US, "%.1f %%", sl)),
            LX + LW, 546f, 42f, slc, Paint.Align.RIGHT, 0.02f)

        // 四格：THR / OIL / AMB / INTAKE
        val labels = arrayOf("THR", "OIL", "AMB", "INTAKE")
        val vals = floatArrayOf(st.obdThrottle, st.obdOil, st.obdAmb, st.obdIntake)
        for (i in 0 until 4) {
            val bx = LX + i * 168f
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(1f)
            p.color = RULE
            c.drawRect(x(bx), y(620f), x(bx + 152f), y(710f), p)
            p.style = Paint.Style.FILL
            text(c, labels[i], bx + 12f, 650f, 20f, MUTED, Paint.Align.LEFT, 0.10f)
            val vv = vals[i]
            val has = vv > -100f
            text(c, if (!has) "--" else if (i == 0) String.format(Locale.US, "%.0f%%", vv) else String.format(Locale.US, "%.0f°", vv),
                bx + 76f, 696f, if (has) 44f else 30f, if (has) INK else MUTED, Paint.Align.CENTER, 0.02f)
        }

        rule(c, LX, LX + LW, 750f)
        kvRow(c, LX, 806f, LW, "ODO", if (st.odoKm < 0f) "--" else String.format(Locale.US, "%,.1f", st.odoKm).replace(',', ' '), "km", INK)
        kvRow(c, LX, 862f, LW, "SERVICE", f0(st.srvKm), "km", INK)
        kvRow(c, LX, 918f, LW, "ENG HRS", f1(st.engHrs), "h", INK)

        // CO2 回原位（条状）
        val co2 = if (NetState.fresh()) NetState.co2 else -1f
        val co2c = when {
            co2 < 0f -> MUTED
            co2 < 600f -> GREEN
            co2 < 800f -> YELLOW
            co2 < 1000f -> AMBER
            else -> RED
        }
        text(c, "CO2", LX, 972f, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        text(c, if (co2 < 0f) "--" else String.format(Locale.US, "%.0f ppm", co2), LX + LW, 972f, 36f, co2c, Paint.Align.RIGHT, 0.02f)
        bar(c, LX, 986f, LW, 8f, if (co2 < 0f) 0f else (co2 / 2500f).coerceIn(0f, 1f), co2c)
    }

    // ------------------------------------------------------------ right

    private fun rightBlock(c: Canvas, net: NetState, st: VehicleState) {
        val fresh = net.ok
        header(c, RX, RW, "BATTERY", YELLOW, 300f)

        // 主体一：混动 SOC + 12V（同块右侧）
        text(c, "HYBRID", RX, 348f, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        val v12 = st.volt12
        val v12c = when {
            v12 < 0f -> MUTED
            v12 < 11.8f -> RED
            v12 < 12.3f -> YELLOW
            v12 > 15.2f -> AMBER
            else -> INK
        }
        text(c, "12V", RX + RW, 348f, 22f, MUTED, Paint.Align.RIGHT, 0.16f)
        text(c, if (v12 < 0f) "--" else String.format(Locale.US, "%.1f", v12),
            RX + RW - 26f, 460f, 56f, v12c, Paint.Align.RIGHT, 0.02f)
        text(c, "V", RX + RW, 460f, 26f, MUTED, Paint.Align.RIGHT, 0.04f)

        val soc = st.soc
        val socW = if (soc < 0f) number(c, "--", RX, 460f, 52f, MUTED)
        else number(c, String.format(Locale.US, "%.0f", soc), RX, 460f, 92f, INK)
        text(c, "%", RX + socW + 10f, 460f, 30f, MUTED, Paint.Align.LEFT, 0.04f)
        bar(c, RX, 486f, RW, 14f, if (soc < 0f) 0f else soc / 100f,
            when {
                soc < 0f -> MUTED
                soc < 20f -> RED
                soc < 40f -> AMBER
                else -> GREEN
            })

        // 主体二：电池包总电量
        text(c, "PACK", RX, 578f, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        val ps = st.psTotal
        val psW = if (ps < 0f) number(c, "--", RX, 690f, 52f, MUTED)
        else number(c, String.format(Locale.US, "%.1f", ps), RX, 690f, 92f, INK)
        text(c, "%", RX + psW + 10f, 690f, 30f, MUTED, Paint.Align.LEFT, 0.04f)
        bar(c, RX, 714f, RW, 14f, if (ps < 0f) 0f else ps / 100f, YELLOW)

        // 预计充满（总电量增速优先，回退输入功率）
        val eta = st.psEtaMin
        when {
            ps >= 99.5f -> text(c, "FULL", RX + RW, 690f, 28f, GREEN, Paint.Align.RIGHT, 0.06f)
            eta > 0f -> text(c, "FULL IN " + fmtEta(eta), RX + RW, 690f, 26f, MUTED, Paint.Align.RIGHT, 0.08f)
        }

        rule(c, RX, RX + RW, 766f)

        // 附属：2000E / 1000 MINI
        text(c, "POWER STATION", RX, 806f, 20f, MUTED, Paint.Align.LEFT, 0.16f)
        text(c, "3056 Wh", RX + RW, 806f, 20f, MUTED, Paint.Align.RIGHT, 0.02f)

        val b2k = st.ble2000
        text(c, "2000E", RX, 856f, 22f, MUTED, Paint.Align.LEFT, 0.16f)
        text(c, if (b2k < 0f) "--" else String.format(Locale.US, "%.0f %%", b2k),
            RX + 300f, 856f, 28f, if (b2k < 0f) MUTED else INK, Paint.Align.RIGHT, 0.02f)
        bar(c, RX + 320f, 838f, 340f, 9f, if (b2k < 0f) 0f else b2k / 100f, YELLOW)

        val b1k = if (fresh) net.b1000 else -1f
        text(c, "1000 MINI", RX, 912f, 22f, MUTED, Paint.Align.LEFT, 0.16f)
        text(c, if (b1k < 0f) "--" else String.format(Locale.US, "%.0f %%", b1k),
            RX + 300f, 912f, 28f, if (b1k < 0f) MUTED else INK, Paint.Align.RIGHT, 0.02f)
        bar(c, RX + 320f, 894f, 340f, 9f, if (b1k < 0f) 0f else b1k / 100f, YELLOW)

        // AC 一行：IN 走 BLE，OUT 用 HUD 的「双电源总输出」口径
        text(c, "AC", RX, 950f, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        if (!fresh) {
            p.style = Paint.Style.STROKE; p.strokeWidth = d(1f); p.color = RULE
            c.drawRect(x(RX + 56f), y(922f), x(RX + 166f), y(974f), p)
            p.style = Paint.Style.FILL
            text(c, "--", RX + 111f, 962f, 26f, MUTED, Paint.Align.CENTER, 0.04f)
        } else if (net.acOn) {
            p.color = BLUE
            c.drawRect(x(RX + 56f), y(922f), x(RX + 166f), y(974f), p)
            text(c, "ON", RX + 111f, 964f, 24f, INK, Paint.Align.CENTER, 0.04f)
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = d(1f); p.color = RULE
            c.drawRect(x(RX + 56f), y(922f), x(RX + 166f), y(974f), p)
            p.style = Paint.Style.FILL
            text(c, "OFF", RX + 111f, 964f, 24f, MUTED, Paint.Align.CENTER, 0.04f)
        }
        // AC 右侧 IN：优先吃 Pi BLE 的 SDC 输入功率
        val pin = if (st.sdcIn >= 0f) st.sdcIn else if (st.psIn >= 0f) st.psIn else if (fresh) net.inW else -1f
        val miniOut = if (fresh) net.miniOutW else -1f
        val pout = if (st.psOut >= 0f || miniOut >= 0f)
            (if (st.psOut >= 0f) st.psOut else 0f) + (if (miniOut >= 0f) miniOut else 0f)
        else -1f
        text(c, if (pin < 0f && pout < 0f) "IN --   OUT --"
            else String.format(Locale.US, "IN %.0f W   OUT %.0f W", if (pin < 0f) 0f else pin, if (pout < 0f) 0f else pout),
            RX + 190f, 962f, 28f, if (pin < 0f && pout < 0f) MUTED else INK, Paint.Align.LEFT, 0.02f)
    }

    private fun header(c: Canvas, hx: Float, hw: Float, label: String, accent: Int, hy: Float) {
        p.color = accent
        c.drawRect(x(hx), y(hy - 22f), x(hx + 14f), y(hy - 8f), p)
        text(c, label, hx + 24f, hy, 32f, INK, Paint.Align.LEFT, 0.18f)
        rule(c, hx, hx + hw, hy + 16f)
    }

    private fun rule(c: Canvas, x0: Float, x1: Float, yy: Float) {
        p.color = RULE
        c.drawRect(x(x0), y(yy), x(x1), y(yy + 1f), p)
    }

    private fun bar(c: Canvas, bx: Float, by: Float, bw: Float, bh: Float, frac: Float, color: Int) {
        p.color = TRACK
        c.drawRect(x(bx), y(by), x(bx + bw), y(by + bh), p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.001f) {
            p.color = color
            c.drawRect(x(bx), y(by), x(bx + bw * f), y(by + bh), p)
        }
    }

    private fun kvRow(c: Canvas, cx: Float, ry: Float, cw: Float, label: String, value: String, unit: String, vc: Int, vs: Float = 42f) {
        text(c, label, cx, ry, 24f, MUTED, Paint.Align.LEFT, 0.16f)
        val dash = value == "--" || value == "—" || value.isEmpty()
        t.isAntiAlias = true; t.typeface = fSans; t.textSize = d(if (dash) vs * 0.66f else vs); t.color = if (dash) MUTED else vc; t.letterSpacing = 0f
        t.textAlign = Paint.Align.RIGHT
        var right = x(cx + cw)
        if (unit.isNotEmpty()) {
            val uw = t.measureText(unit)
            t.textSize = d(26f); t.color = MUTED
            c.drawText(unit, right, y(ry), t)
            right -= uw + d(10f)
            t.textSize = d(vs); t.color = vc
        }
        c.drawText(if (dash) "--" else value, right, y(ry), t)
    }

    private fun number(c: Canvas, v: String, nx: Float, ny: Float, size: Float, color: Int): Float {
        t.isAntiAlias = true; t.typeface = fSans; t.textSize = d(size); t.color = color
        t.textAlign = Paint.Align.LEFT; t.letterSpacing = 0f
        c.drawText(v, x(nx), y(ny), t)
        return t.measureText(v) / s
    }

    private fun text(c: Canvas, s: String, tx: Float, ty: Float, size: Float, color: Int,
                     align: Paint.Align, ls: Float = 0f) {
        t.isAntiAlias = true
        t.typeface = fSans
        t.textSize = d(size)
        t.color = color
        t.textAlign = align
        t.letterSpacing = ls
        c.drawText(s, x(tx), y(ty), t)
    }

    private fun f0(v: Float): String = if (v < 0f || v.isNaN()) "--" else String.format(Locale.US, "%.0f", v)
    private fun f1(v: Float): String = if (v < 0f || v.isNaN()) "--" else String.format(Locale.US, "%.1f", v)

    private fun metric(c: Canvas, v: Float, nx: Float, ny: Float, size: Float, fmt: String): Float {
        return if (v < 0f || v.isNaN()) number(c, "--", nx, ny, size * 0.5f, MUTED)
        else number(c, String.format(Locale.US, fmt, v), nx, ny, size, INK)
    }

    private fun updateClock(now: Long) {
        val sec = now / 1000L
        if (sec == lastClockSec) return
        lastClockSec = sec
        val cal = java.util.Calendar.getInstance()
        clock = String.format(Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        dateLine = df.format(Date()).uppercase(Locale.US)
    }
}
