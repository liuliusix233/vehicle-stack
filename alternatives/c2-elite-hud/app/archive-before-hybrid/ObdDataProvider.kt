package com.vox.hud

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads OBD data from the Pi proxy (<LAN_IP>:8080/obd).
 *
 * Uses /system/bin/busybox wget with 3s timeout.
 */
class ObdDataProvider(private val context: Context) {

    companion object {
        private const val TAG = "ObdProvider"
    }

    fun fetch(): ObdSnapshot? {
        return try {
            Log.d(TAG, "fetch() start")
            val pb = ProcessBuilder(
                "/system/bin/busybox", "wget", "-q", "-T", "3",
                "-O-", "http://<LAN_IP>:8080/obd"
            )
            pb.redirectErrorStream(true)
            val process = pb.start()
            val json = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            Log.d(TAG, "fetch() done exit=$exit len=${json.length}")

            if (exit != 0 || json.isBlank()) {
                Log.d(TAG, "wget result empty")
                return null
            }

            ObdSnapshot.fromJson(json)

        } catch (e: Exception) {
            Log.e(TAG, "Fetch error: ${e.message}")
            null
        }
    }

    fun generateMockData(): ObdSnapshot {
        val mockSpeed = (20 + (System.currentTimeMillis() / 1000 % 80)).toInt()
        val mockRpm = (1200 + (System.currentTimeMillis() / 500 % 2000)).toInt()
        return ObdSnapshot(
            speedKmh = mockSpeed,
            rpm = mockRpm,
            batteryVoltage = 12.5f + (System.currentTimeMillis() % 1000) / 1000f * 2f,
            fuelLevel = 65,
            odometer = 30169,
            fuelConsumption = 7.6f
        )
    }
}
