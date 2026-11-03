package com.vox.hud

import android.util.Log
import java.io.InputStreamReader

/**
 * Fetches HA supplement data (outdoor power, heading) from Pi proxy.
 *
 * Uses busybox wget with 3s timeout. Called at low frequency (~3s).
 */
class PiDataProvider {

    companion object {
        private const val TAG = "PiProvider"
    }

    fun fetch(): ObdSnapshot? {
        return try {
            val pb = ProcessBuilder(
                "/system/bin/busybox", "wget", "-q", "-T", "3",
                "-O-", "http://<LAN_IP>:8080/obd"
            )
            pb.redirectErrorStream(true)
            val process = pb.start()
            val json = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()

            if (exit != 0 || json.isBlank()) return null
            ObdSnapshot.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch error: ${e.message}")
            null
        }
    }
}
