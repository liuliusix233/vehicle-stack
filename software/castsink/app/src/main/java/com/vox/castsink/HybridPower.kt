package com.vox.castsink

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v84 48V 轻混功率反推
 * 物理模型给细节，SOC(0.39%/格, 1.76Wh) 才是唯一的真值锚点：
 *  - 只在 SOC 真正跳格时做一次小幅修正（±120W/格，总量封顶 ±400W，并缓慢回零）
 *  - 车速≈0、SOC 20s 不动 → 强制收敛到很小的静止基线（48V 此时基本不流动）
 */
object HybridPower {
    private const val MASS = 1900.0
    private const val AERO = 0.5 * 1.2 * 0.33 * 2.6
    private const val CRR = 0.011
    private const val G = 9.81
    private const val PACK_WH = 450.0
    private const val MAX_ASSIST = 8000.0
    private const val MAX_REGEN = 8000.0
    private const val ETA_REGEN = 0.62

    @Volatile var powerW = 0.0
    @Volatile var throttleW = 0.0
    @Volatile var regenW = 0.0
    @Volatile var trimW = 0.0

    private var lastV = -1.0
    private var accel = 0.0
    private var socRef = -1.0
    private var whSinceStep = 0.0
    private var lastStepAt = 0L
    private var nowMs = 0L

    fun update(dt: Double, speedKmh: Float, rpm: Float, throttle: Float, load: Float, soc: Float) {
        nowMs = System.currentTimeMillis()
        val v = (speedKmh.coerceAtLeast(0f) / 3.6f).toDouble()
        if (lastV < 0.0) lastV = v
        val rawA = if (dt > 0.0) (v - lastV) / dt else 0.0
        accel += (rawA.coerceIn(-4.0, 4.0) - accel) * 0.25
        lastV = v

        val wheel = MASS * accel * v + AERO * v * v * v + CRR * MASS * G * v
        val thr = throttle.coerceIn(0f, 100f).toDouble()
        val r = rpm.coerceIn(0f, 7000f).toDouble()
        val socPct = soc.coerceIn(0f, 100f).toDouble()

        val assistSoc = if (socPct <= 45f) ((socPct - 25f) / 20f).coerceIn(0.0, 1.0) else 1.0
        val regenSoc = if (socPct >= 85f) ((100f - socPct) / 15f).coerceIn(0.0, 1.0) else 1.0

        val thrOpen = ((thr - 20.0) / 60.0).coerceIn(0.0, 1.0)
        val rpmReady = ((r - 900.0) / 1800.0).coerceIn(0.0, 1.0)
        val need = (wheel.coerceAtLeast(0.0) / 4000.0).coerceIn(0.0, 1.0)
        var assist = MAX_ASSIST * thrOpen * rpmReady * (0.35 + 0.65 * need) * assistSoc
        if (v < 2.0 && thr > 25.0 && r > 700.0) assist = max(assist, 1500.0 * thrOpen * assistSoc)
        assist = min(assist, MAX_ASSIST)

        var regen = 0.0
        if (thr < 8.0 && v > 3.0) {
            val decel = (-accel).coerceAtLeast(0.0)
            val kin = MASS * decel * v * ETA_REGEN
            val base = 220.0 * (v / 10.0).coerceAtMost(3.0)
            regen = min((kin + base) * regenSoc, MAX_REGEN)
        }

        var maint = 0.0
        if (r > 650.0 && socPct < 50.0) maint = (400.0 * (50.0 - socPct) / 20.0).coerceIn(0.0, 400.0)

        // 12V 负载由 48V 经 DCDC：发动机一转就有很小的持续放电
        val aux = if (r > 400.0) -(110.0 + 40.0 * (v / 10.0).coerceAtMost(2.0)) else 0.0

        throttleW = -assist
        regenW = regen + maint

        // trim 自然回零（tau≈40s）
        trimW *= (1.0 - (dt / 40.0)).coerceIn(0.0, 1.0)

        // SOC 跳格 = 唯一真值：小幅修正，绝不连续反馈（那是自激振荡的来源）
        if (socPct > 0.0) {
            if (socRef < 0.0) { socRef = socPct; whSinceStep = 0.0; lastStepAt = nowMs }
            if (abs(socPct - socRef) >= 0.39) {
                val actual = (socPct - socRef) / 100.0 * PACK_WH
                val err = actual - whSinceStep
                trimW = (trimW + (err * 220.0).coerceIn(-120.0, 120.0)).coerceIn(-400.0, 400.0)
                socRef = socPct
                whSinceStep = 0.0
                lastStepAt = nowMs
            }
        }

        // 静止 + SOC 长时间不动 → 真实功率必然接近 0，收敛掉历史偏置
        if (nowMs - lastStepAt > 20000L && v < 1.5 && thr < 5.0) {
            trimW *= (1.0 - (dt / 5.0)).coerceIn(0.0, 1.0)
        }

        val raw = (throttleW + regenW + aux + trimW).coerceIn(-MAX_ASSIST, MAX_REGEN)
        powerW += (raw - powerW) * (dt / (dt + 1.2))
        whSinceStep += powerW * dt / 3600.0
    }
}
