package com.vox.hud

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads AIOBD logcat lines directly on-device.
 *
 * Supports two formats:
 *  - AIOBD## key=value batches (legacy)
 *  - MainScreenFragment##update,StatusInfo{...} (AIOBD 2026-10 new format)
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
                    Log.d(TAG, "Starting logcat reader")
                    val startTime = java.text.SimpleDateFormat(
                        "MM-dd HH:mm:ss.SSS", java.util.Locale.US
                    ).format(java.util.Date())
                    val cmd = arrayOf("logcat", "-T", startTime, "-s", "AIOSLOG:*")
                    val pb = ProcessBuilder(*cmd)
                    pb.redirectErrorStream(true)
                    process = pb.start()
                    reader = BufferedReader(InputStreamReader(process!!.inputStream))

                    Log.d(TAG, "logcat reader started (from $startTime), reading...")

                    var line = reader.readLine()
                    while (running && line != null) {
                        val l = line
                        if (l.contains("StatusInfo{")) {
                            val snapshot = ObdSnapshot.fromStatusInfo(l)
                            if (snapshot != null) onSnapshot(snapshot)
                        } else if (l.contains("AIOBD##")) {
                            if (l.contains("=========")) {
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
                                            wifiOn = true
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
                    if (running) Log.e(TAG, "Reader error: ${e.message}")
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
