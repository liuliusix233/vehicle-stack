package com.vox.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import dalvik.system.PathClassLoader

/**
 * 2026-10-19: Bind to com.ileja.aicore.data.DataRepertoryService and subscribe
 * to com.ileja.aicar.obd.real (AIObdRealtimeData Parcelable). This is the same
 * data bus the stock desktop uses; no root/logcat parsing required.
 */
class VehicleDataClient(private val onSnapshot: (ObdSnapshot) -> Unit) {
    companion object {
        private const val TAG = "VehicleData"
        private const val PKG = "com.ileja.aicore"
        private const val SERVICE = "com.ileja.aicore.data.DataRepertoryService"
        private const val FILTER = "com.ileja.aicar.obd.real"
        private const val DESCRIPTOR = "com.ileja.aicore.data.IDataRepertory"
        private const val CALLBACK_DESCRIPTOR = "com.ileja.aicore.data.IDataCallback"
        private const val TX_REGISTER = 2
        private const val TX_GET = 4
        private const val DATA_KEY = "ai_data"
        private const val AICORE_APK = "/system/app/HudcoreAicore/HudcoreAicore.apk"
    }

    private var ctx: Context? = null
    private var remote: IBinder? = null
    private var loader: ClassLoader? = null
    private var frames = 0L

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                data.readString()
                val bundle = if (data.readInt() != 0) data.readBundle(loader) else null
                if (bundle != null) parse(bundle)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service
            Log.i(TAG, "connected to $PKG/$SERVICE")
            register()
            getLatest()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            Log.w(TAG, "data service disconnected")
        }
    }

    fun start(context: Context) {
        ctx = context.applicationContext
        try {
            loader = PathClassLoader(AICORE_APK, ClassLoader.getSystemClassLoader())
        } catch (e: Exception) {
            Log.w(TAG, "classloader failed: ${e.message}")
        }
        try {
            val intent = Intent().setClassName(PKG, SERVICE)
            val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService=$ok")
        } catch (e: Exception) {
            Log.e(TAG, "bindService failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            remote = null
            ctx?.unbindService(conn)
        } catch (_: Exception) {
        }
        ctx = null
    }

    private fun register() {
        val b = remote ?: return
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(DESCRIPTOR)
            d.writeString(FILTER)
            d.writeStrongBinder(callback)
            b.transact(TX_REGISTER, d, r, 0)
            r.readException()
            Log.i(TAG, "registered filter=$FILTER")
        } catch (e: Exception) {
            Log.e(TAG, "register failed: ${e.message}")
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    private fun getLatest() {
        val b = remote ?: return
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(DESCRIPTOR)
            d.writeString(FILTER)
            b.transact(TX_GET, d, r, 0)
            r.readException()
            if (r.readInt() != 0) {
                val bundle = r.readBundle(loader)
                if (bundle != null) parse(bundle)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getLatest failed: ${e.message}")
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    private fun parse(bundle: Bundle) {
        try {
            bundle.classLoader = loader
            val obj = bundle.get(DATA_KEY) ?: return
            val cls = obj.javaClass

            fun f(name: String): Float = try {
                cls.getField(name).getFloat(obj)
            } catch (_: Exception) {
                -1f
            }

            val speed = f("obdSpeed")
            val rpm = f("obdRotateSpeed")
            if (speed < 0f && rpm < 0f) return

            val snapshot = ObdSnapshot(
                speedKmh = if (speed >= 0f) speed.toInt() else -1,
                rpm = if (rpm >= 0f) rpm.toInt() else -1,
                coolantTemp = f("obdCoolantTemprature").let { if (it >= 0f) it.toInt() else -1 },
                batteryVoltage = f("obdBattery"),
                engineLoad = f("obdEngineLoadRatio").let { if (it >= 0f) it.toInt() else -1 },
                throttlePos = f("obdThrottleOpenRatio").let { if (it >= 0f) it.toInt() else -1 },
                fuelConsumption = f("obdRealtimeFuelConsume"),
                odometer = f("obdTotalDrivingRange"),
                wifiOn = true
            )
            frames++
            if (frames <= 3L || frames % 60L == 0L) {
                Log.i(TAG, "frame#$frames speed=${snapshot.speedKmh} rpm=${snapshot.rpm} coolant=${snapshot.coolantTemp} batt=${snapshot.batteryVoltage}")
            }
            onSnapshot(snapshot)
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}")
        }
    }
}
