package com.vox.hud

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads AIOBD## logcat lines directly on-device via su.
 *
 * Spawns `su -c 'logcat -s AIOSLOG:*'` as a background process,
 * accumulates lines between batch boundaries (====== separator),
 * and invokes onSnapshot callback with the parsed ObdSnapshot.
 *
 * Latency: ≈ AIOS refresh cycle (~1s), NO Pi/network middleman.
 */
class LocalLogcatReader(private val onSnapshot: (ObdSnapshot) -> Unit) {

    companion object {
        private const val TAG = "LocalLogcat"
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var process: java.lang.Process? = null

    fun start() {
        if (running) return
        running = true

        thread = object : Thread("AIOBD-Logcat") {
            override fun run() {
                var reader: BufferedReader? = null
                val batch = mutableListOf<String>()

                try {
                    Log.d(TAG, "Starting su logcat reader")
                    // Must escape the command properly for su -c
                    val cmd = arrayOf("su", "-c", "logcat -s AIOSLOG:*")
                    val pb = ProcessBuilder(*cmd)
                    pb.redirectErrorStream(true)
                    process = pb.start()
                    reader = BufferedReader(InputStreamReader(process!!.inputStream))

                    Log.d(TAG, "su logcat reader started, reading...")

                    var line = reader.readLine()
                    while (running && line != null) {
                        val l = line
                        if (l.contains("AIOBD##")) {
                            if (l.contains("=========")) {
                                // Batch boundary — parse accumulated lines
                                if (batch.isNotEmpty()) {
                                    val snapshot = ObdSnapshot.fromLogcatLines(batch.toList())
                                    batch.clear()
                                    if (snapshot != null) {
                                        val enriched = ObdSnapshot(
                                            speedKmh = snapshot.speedKmh,
                                            rpm = snapshot.rpm,
                                            coolantTemp = snapshot.coolantTemp,
                                            batteryVoltage = snapshot.batteryVoltage,
                                            fuelLevel = snapshot.fuelLevel,
                                            engineLoad = snapshot.engineLoad,
                                            throttlePos = snapshot.throttlePos,
                                            intakeTemp = snapshot.intakeTemp,
                                            oilTemp = snapshot.oilTemp,
                                            odometer = snapshot.odometer,
                                            ambientTemp = snapshot.ambientTemp,
                                            fuelConsumption = snapshot.fuelConsumption,
                                            // heading/power/gear unavailable locally — default
                                            heading = 0,
                                            power = -1,
                                            gear = 0,
                                            // Local mode: always connected
                                            wifiOn = true,
                                            piOn = true
                                        )
                                        onSnapshot(enriched)
                                    }
                                }
                            } else {
                                batch.add(l)
                            }
                        }
                        line = reader.readLine()
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "Reader error: ${e.message}")
                    }
                } finally {
                    try { reader?.close() } catch (_: Exception) {}
                    try { process?.destroy() } catch (_: Exception) {}
                    Log.d(TAG, "Reader stopped")
                }
            }
        }.apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        process?.destroy()
        thread = null
        process = null
    }
}
