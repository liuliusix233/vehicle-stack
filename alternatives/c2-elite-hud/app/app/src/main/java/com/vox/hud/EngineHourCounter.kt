package com.vox.hud

import android.content.SharedPreferences
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Engine hour counter — tracks engine running time since last service.
 *
 * Accumulates in seconds whenever OBD reports RPM > 0.
 * Persists to SharedPreferences every 30 seconds.
 * Seeded with estimated post-service hours on first run.
 *
 * ## Usage
 * ```kotlin
 * val counter = EngineHourCounter(prefs)
 * // On every OBD update (~1s):
 * counter.tick(rpm, odometerKm)
 * // Display:
 * val hours = counter.getServiceHours()
 * val svcKm = counter.getServiceKm(currentOdo)
 * ```
 */
class EngineHourCounter(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "EngineHours"

        // SharedPreferences keys (v2 — reset old cache)
        private const val KEY_TOTAL_SECONDS = "eng_hrs_total_sec_v2"
        private const val KEY_TRIP_SECONDS  = "eng_hrs_trip_sec_v2"
        private const val KEY_SERVICE_ODO   = "eng_hrs_svc_odo_v2"
        private const val KEY_SEED_SET      = "eng_hrs_seed_set_v2"

        /**
         * Default seed: estimated 137 post-service engine hours
         * based on May~July driving data (5209 km at 58/35/42 km/h avg)
         * including idle factor.
         */
        private const val DEFAULT_SEED_SECONDS = 137L * 3600  // = 493,200s

        /**
         * Service odometer baseline from OBD data:
         * distance_since_dtc_clear was 5209km @ odo ~30342 (July 7)
         * → service odo = 30342 - 5209 = 25133 km
         * Car now at ~30653 km → post-service distance ≈ 5520 km
         */
        private const val DEFAULT_SERVICE_ODO = 25133f

        // Persist interval
        private const val PERSIST_INTERVAL_MS = 30_000L

        // Max single tick advance (guard against time jumps)
        private const val MAX_TICK_SECONDS = 5
    }

    // ── Volatile state (read-safe from any thread) ──

    /** Total engine seconds accumulated since last service. */
    @Volatile var totalSeconds: Long = DEFAULT_SEED_SECONDS
        private set

    /** Current trip engine seconds (resets on car restart). */
    @Volatile var tripSeconds: Long = 0
        private set

    /** Odometer snapshot taken at last service start. */
    @Volatile var serviceOdometerKm: Float = -1f
        private set

    // ── Internal state (synchronized for multi-thread safety) ──
    // Millisecond accumulator for sub-second precision.

    private var lock = Any()
    private var totalMs: Long = 0L
    private var tripMs: Long = 0L
    private var lastTickMs = 0L
    private var lastPersistMs = 0L

    // ── Init ──

    init {
        totalSeconds = prefs.getLong(KEY_TOTAL_SECONDS, DEFAULT_SEED_SECONDS)
        tripSeconds  = prefs.getLong(KEY_TRIP_SECONDS, 0)
        serviceOdometerKm = prefs.getFloat(KEY_SERVICE_ODO, DEFAULT_SERVICE_ODO)
        totalMs = totalSeconds * 1000L
        tripMs  = tripSeconds * 1000L

        if (!prefs.getBoolean(KEY_SEED_SET, false)) {
            prefs.edit().putBoolean(KEY_SEED_SET, true).apply()
            Log.i(TAG, "First run — seeded engine hours: " +
                    "${totalSeconds}s (${formatHours(totalSeconds)}h)")
        } else {
            Log.d(TAG, "Restored: ${totalSeconds}s (${formatHours(totalSeconds)}h)")
        }
    }

    // ── Public API ──

    /**
     * Call on every OBD update. Thread-safe.
     *
     * Efficient: ~5 synchronized blocks/sec when engine running.
     *
     * @param rpm       engine RPM (≤0 = engine off)
     * @param odometer  current odometer in km (from logcat or HA)
     */
    fun tick(rpm: Int, odometer: Float) {
        synchronized(lock) {
            val now = System.currentTimeMillis()

            if (rpm > 0) {
                if (lastTickMs > 0) {
                    val elapsedMs = minOf(now - lastTickMs, 5000L)
                    if (elapsedMs > 0) {
                        totalMs += elapsedMs
                        tripMs += elapsedMs
                        totalSeconds = totalMs / 1000L
                        tripSeconds = tripMs / 1000L
                    }
                } else {
                    // First tick with engine on — count 1s as baseline
                    totalMs += 1000L
                    tripMs += 1000L
                    totalSeconds = totalMs / 1000L
                    tripSeconds = tripMs / 1000L
                }
            }

            lastTickMs = now

            // Capture fallback service odo (only if still unset after default)
            if (odometer > 0 && serviceOdometerKm <= 0) {
                serviceOdometerKm = odometer
                prefs.edit().putFloat(KEY_SERVICE_ODO, odometer).apply()
                Log.d(TAG, "Service odo fallback: ${odometer}km")
            }

            // Periodic persist
            if (now - lastPersistMs >= PERSIST_INTERVAL_MS) {
                persist()
                lastPersistMs = now
            }
        }
    }

    /** Force-persist current accumulator state. */
    fun persist() {
        prefs.edit()
            .putLong(KEY_TOTAL_SECONDS, totalSeconds)
            .putLong(KEY_TRIP_SECONDS, tripSeconds)
            .apply()
    }

    // ── Getters ──

    /** Post-service engine hours (decimal). */
    fun getServiceHours(): Float = totalSeconds / 3600f

    /** Current trip duration in minutes. */
    fun getTripMinutes(): Float = tripSeconds / 60f

    /**
     * Distance driven since service start odometer baseline.
     * Returns -1 if baseline not yet captured.
     */
    fun getServiceKm(currentOdometer: Float): Float {
        return if (serviceOdometerKm > 0 && currentOdometer > 0) {
            maxOf(0f, currentOdometer - serviceOdometerKm)
        } else -1f
    }

    // ── Calibration / Reset (via Intent) ──

    /**
     * Override total engine hours (e.g. after reading ECU value).
     * Does NOT reset odometer baseline.
     */
    fun calibrateHours(hours: Float) {
        totalSeconds = (hours * 3600).toLong()
        totalMs = totalSeconds * 1000L
        persist()
        Log.i(TAG, "Calibrated engine hours → ${hours}h")
    }

    /**
     * Reset for new service interval.
     * Zeros engine hours and sets new odometer baseline.
     *
     * ⚠️ 2026-08-09 FIX: must also zero the in-memory ms accumulators
     * (totalMs/tripMs/lastTickMs). Previously only totalSeconds/tripSeconds
     * and prefs were cleared, so the next tick() re-added the old ms
     * accumulator back → engine hours reverted to pre-service value.
     */
    fun resetForNewService(currentOdometer: Float) {
        synchronized(lock) {
            totalSeconds = 0
            tripSeconds = 0
            totalMs = 0
            tripMs = 0
            lastTickMs = 0
            serviceOdometerKm = currentOdometer
            prefs.edit()
                .putLong(KEY_TOTAL_SECONDS, 0)
                .putLong(KEY_TRIP_SECONDS, 0)
                .putFloat(KEY_SERVICE_ODO, currentOdometer)
                .putBoolean(KEY_SEED_SET, true)
                .commit()
            Log.i(TAG, "Reset for new service @ ${currentOdometer}km")
        }
    }

    // ── Helpers ──

    private fun formatHours(sec: Long): String =
        String.format("%.1f", sec / 3600f)
}
