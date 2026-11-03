package com.vox.castsink

import android.content.SharedPreferences
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * 照搬 HUD EngineHourCounter：RPM>0 累计秒数，30s 落盘。
 * 种子 137h，保养里程基线 25133 km（与 HUD 一致）。
 */
class EngineHourCounter(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "EngineHours"
        private const val KEY_TOTAL_SECONDS = "eng_hrs_total_sec_v3"
        private const val KEY_TRIP_SECONDS = "eng_hrs_trip_sec_v3"
        private const val KEY_SERVICE_ODO = "eng_hrs_svc_odo_v3"
        private const val KEY_SEED_SET = "eng_hrs_seed_set_v3"
        // 真实保养数据：68.2h / 3229km（odo 41723.3 - 3229 = 38494.3）
        private const val DEFAULT_SEED_SECONDS = 245520L          // 68.2h
        private const val DEFAULT_SERVICE_ODO = 38494.3f
        private const val PERSIST_INTERVAL_MS = 30_000L
    }

    @Volatile var totalSeconds: Long = DEFAULT_SEED_SECONDS
        private set
    @Volatile var tripSeconds: Long = 0
        private set
    @Volatile var serviceOdometerKm: Float = -1f
        private set

    private val lock = Any()
    private var totalMs: Long = 0L
    private var tripMs: Long = 0L
    private var lastTickMs = 0L
    private var lastPersistMs = 0L

    init {
        totalSeconds = prefs.getLong(KEY_TOTAL_SECONDS, DEFAULT_SEED_SECONDS)
        tripSeconds = prefs.getLong(KEY_TRIP_SECONDS, 0)
        serviceOdometerKm = prefs.getFloat(KEY_SERVICE_ODO, DEFAULT_SERVICE_ODO)
        totalMs = totalSeconds * 1000L
        tripMs = tripSeconds * 1000L
        if (!prefs.getBoolean(KEY_SEED_SET, false)) {
            prefs.edit().putBoolean(KEY_SEED_SET, true).apply()
            Log.i(TAG, "First run — seeded engine hours: " + totalSeconds + "s")
        }
    }

    fun tick(rpm: Int, odometer: Float) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (rpm > 0) {
                if (lastTickMs > 0) {
                    val elapsedMs = min(now - lastTickMs, 5000L)
                    if (elapsedMs > 0) {
                        totalMs += elapsedMs
                        tripMs += elapsedMs
                        totalSeconds = totalMs / 1000L
                        tripSeconds = tripMs / 1000L
                    }
                } else {
                    totalMs += 1000L
                    tripMs += 1000L
                    totalSeconds = totalMs / 1000L
                    tripSeconds = tripMs / 1000L
                }
            }
            lastTickMs = now
            if (odometer > 0 && serviceOdometerKm <= 0) {
                serviceOdometerKm = odometer
                prefs.edit().putFloat(KEY_SERVICE_ODO, odometer).apply()
            }
            if (now - lastPersistMs >= PERSIST_INTERVAL_MS) {
                persist()
                lastPersistMs = now
            }
        }
    }

    fun persist() {
        prefs.edit()
            .putLong(KEY_TOTAL_SECONDS, totalSeconds)
            .putLong(KEY_TRIP_SECONDS, tripSeconds)
            .apply()
    }

    fun getServiceHours(): Float = totalSeconds / 3600f

    fun getServiceKm(currentOdometer: Float): Float {
        return if (serviceOdometerKm > 0 && currentOdometer > 0) max(0f, currentOdometer - serviceOdometerKm) else -1f
    }
}
