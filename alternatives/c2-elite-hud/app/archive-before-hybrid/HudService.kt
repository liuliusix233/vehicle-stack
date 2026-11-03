package com.vox.hud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground service: polls Pi proxy for OBD data every second,
 * broadcasts to MainActivity.
 */
class HudService : Service() {

    companion object {
        private const val TAG = "HudService"
        private const val CHANNEL_ID = "hud_obd"
        private const val NOTIFY_ID = 1001

        const val ACTION_UPDATE = "com.vox.hud.UPDATE"
        const val EXTRA_JSON = "json"
        const val EXTRA_CONNECTED = "connected"
    }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollInterval = 1000L

    private lateinit var dataProvider: ObdDataProvider
    private lateinit var sensorManager: SensorManager
    private var consecutiveFails = 0
    private var mockMode = false

    /** Cache the last good snapshot to survive proxy restarts. */
    private var lastGoodSnapshot: ObdSnapshot? = null

    /** Real heading from HUD orientation sensor (or -1 if unavailable). */
    @Volatile private var sensorHeading: Float = -1f

    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ORIENTATION) {
                // values[0] = azimuth: 0=North, 90=East, 180=South, 270=West
                sensorHeading = event.values[0]
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val pollTask = object : Runnable {
        override fun run() {
            poll()
            pollHandler.postDelayed(this, pollInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        dataProvider = ObdDataProvider(this)
        createNotificationChannel()

        // Register orientation sensor for real heading
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val orient = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        if (orient != null) {
            sensorManager.registerListener(
                headingListener, orient,
                SensorManager.SENSOR_DELAY_UI  // ~60ms updates, fine for HUD
            )
            Log.d(TAG, "Orientation sensor registered")
        } else {
            Log.w(TAG, "No orientation sensor available")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        startForeground(NOTIFY_ID, buildNotification())
        pollHandler.removeCallbacks(pollTask)
        // Start with cached data if available, then poll
        lastGoodSnapshot?.let { broadcastUpdate(it) }
        pollHandler.post(pollTask)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        pollHandler.removeCallbacks(pollTask)
        try { sensorManager.unregisterListener(headingListener) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun poll() {
        val bgThread = object : Thread() {
            override fun run() {
                try {
                    val snapshot = dataProvider.fetch()

                    if (snapshot != null) {
                        consecutiveFails = 0
                        mockMode = false
                        // Override heading with real sensor data
                        val heading = sensorHeading
                        val merged = if (heading >= 0) {
                            ObdSnapshot(
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
                                heading = heading.toInt(),
                                power = snapshot.power,
                                wifiOn = snapshot.wifiOn,
                                piOn = snapshot.piOn
                            )
                        } else { snapshot }
                        // Cache this good snapshot
                        lastGoodSnapshot = merged
                        pollHandler.post { broadcastUpdate(merged) }
                        return
                    }

                    consecutiveFails++
                    if (consecutiveFails > 10) {
                        mockMode = true
                        val mock = dataProvider.generateMockData()
                        val heading = sensorHeading
                        val merged = if (heading >= 0) {
                            ObdSnapshot(
                                speedKmh = mock.speedKmh,
                                rpm = mock.rpm,
                                batteryVoltage = mock.batteryVoltage,
                                fuelLevel = mock.fuelLevel,
                                odometer = mock.odometer,
                                fuelConsumption = mock.fuelConsumption,
                                heading = heading.toInt(),
                                wifiOn = false,
                                piOn = false
                            )
                        } else { mock }
                        pollHandler.post { broadcastUpdate(merged) }
                    } else {
                        // Use cached data for up to 10s while reconnecting
                        val cached = lastGoodSnapshot
                        if (cached != null) {
                            pollHandler.post { broadcastUpdate(cached) }
                        } else {
                            val offline = ObdSnapshot(wifiOn = false, piOn = false)
                            pollHandler.post { broadcastUpdate(offline) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                    val offline = ObdSnapshot(wifiOn = false, piOn = false)
                    pollHandler.post { broadcastUpdate(offline) }
                }
            }
        }
        bgThread.start()
    }

    private fun broadcastUpdate(snapshot: ObdSnapshot) {
        sendBroadcast(Intent(ACTION_UPDATE).apply {
            putExtra(EXTRA_JSON, snapshot.toJson())
            putExtra(EXTRA_CONNECTED, snapshot.piOn)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "HUD OBD",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle("HUD")
            .setContentText(if (mockMode) "演示模式" else "OBD运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
