package com.vox.castsink

/**
 * 12V 电瓶保护（v67）：
 *  1) 深亏电：<11.8V 连续 20s（任何时候都不容忍）
 *  2) 熄火：<12.9V + 转速为 0 + 车速≈0，连续 120s（容忍自动启停/等红灯）
 *  3) 连续 10min 完全拿不到 OBD 数据（电压和转速都无效）
 * 行驶中（speed>3km/h）永不触发低压退出。
 */
object PowerGuard {
    private const val LOW_V = 12.9f
    private const val HOLD_MS = 120000L
    private const val DEEP_V = 11.8f
    private const val DEEP_MS = 20000L
    private const val NO_DATA_MS = 600000L
    private val startedAt = System.currentTimeMillis()
    @Volatile private var lowSince = 0L
    @Volatile private var deepSince = 0L
    @Volatile private var lastValid = 0L

    fun check(now: Long, v: Float, rpm: Float, speed: Float): Boolean {
        if (v > 0.1f || rpm > 0f) lastValid = now

        // 1) 深亏电
        if (v > 0.1f && v < DEEP_V) {
            if (deepSince == 0L) deepSince = now
            if (now - deepSince >= DEEP_MS) return true
        } else {
            deepSince = 0L
        }

        // 2) 熄火判定：低压 + 无转速 + 静止，且持续 2 分钟（自动启停通常 <90s）
        val engineOff = rpm <= 0f
        val moving = speed > 3f
        if (v > 0.1f && v < LOW_V && engineOff && !moving) {
            if (lowSince == 0L) lowSince = now
            if (now - lowSince >= HOLD_MS) return true
        } else {
            lowSince = 0L
        }

        // 3) 长时间无任何 OBD 数据
        val base = if (lastValid > 0L) lastValid else startedAt
        return now - base >= NO_DATA_MS
    }
}
