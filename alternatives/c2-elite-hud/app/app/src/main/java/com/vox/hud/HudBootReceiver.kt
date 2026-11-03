package com.vox.hud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Creates notification channel on boot, enables WiFi, and auto-launches the dashboard.
 */
class HudBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Enable WiFi — HUD doesn't auto-connect without original launcher
            try {
                val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (!wifi.isWifiEnabled) {
                    wifi.isWifiEnabled = true
                }
            } catch (e: Exception) {
                android.util.Log.e("HudBoot", "WiFi enable failed: ${e.message}")
            }

            // Notification channel for HudService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    "hud_obd", "HUD OBD",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(ch)
            }

            // Auto-start dashboard (deferred to let WiFi connect)
            val ctx = context.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (e: Exception) {
                    android.util.Log.e("HudBoot", "Start failed: ${e.message}")
                }
            }, 5000)
        }
    }
}
