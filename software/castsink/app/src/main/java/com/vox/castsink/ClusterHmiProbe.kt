package com.vox.castsink

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.lang.reflect.Method

/**
 * GM ClusterHMI probe (mirrors com.cadillac.wallpaper cluster capability).
 * System lib: /system/framework/info3_cluster.jar (gm.cluster.ClusterHmiManager)
 * System service: clusterService -> gm.cluster.IClusterHmi
 * Reflection only: never crashes when the lib is absent.
 */
object ClusterHmiProbe {

    private const val TAG = "ClusterHmiProbe"
    private const val MANAGER = "gm.cluster.ClusterHmiManager"

    private fun findMethod(c: Class<*>, name: String): Method? {
        for (m in c.methods) if (m.name == name) return m
        return null
    }

    private fun safe(label: String, body: () -> Any?): String {
        return try {
            val v = body()
            val s = label + " = " + v
            Log.i(TAG, s)
            s
        } catch (t: Throwable) {
            val s = label + " !! " + t.javaClass.simpleName + ": " + t.message
            Log.w(TAG, s)
            s
        }
    }

    private fun service(): Any? {
        val cls = Class.forName(MANAGER)
        val inst = cls.getMethod("getInstance").invoke(null)
        Log.i(TAG, "manager=" + inst)
        return cls.getMethod("getService").invoke(inst)
    }

    fun probe(ctx: Activity): String {
        val sb = StringBuilder()
        try {
            Class.forName(MANAGER)
        } catch (t: Throwable) {
            return "ClusterHmiManager NOT loadable: " + t.javaClass.simpleName
        }
        sb.append("lib OK\n")
        val svc = try { service() } catch (t: Throwable) {
            return sb.append("getService failed: " + t.javaClass.simpleName + "\n").toString()
        }
        if (svc == null) return sb.append("service=null\n").toString()
        sb.append("service=" + svc.javaClass.name + "\n")
        val names = listOf("isClusterServiceReady", "getCurrentScreenId", "getDefaultScreenId",
            "getDisplayInfos", "getClusterConfig", "getHudConfig",
            "isClusterInMinimalistMode", "isHudOrOverlay")
        for (n in names) {
            val m = findMethod(svc.javaClass, n)
            if (m == null) { sb.append(n + ": no method\n"); continue }
            sb.append(safe(n) { m.invoke(svc) }).append("\n")
        }
        return sb.toString()
    }

    fun testBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(1024, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0, 122, 255))
        val p = Paint()
        p.color = Color.WHITE
        p.textSize = 72f
        c.drawText("CastSink TEST", 70f, 240f, p)
        p.textSize = 40f
        c.drawText("ClusterHMI uploadBitmap", 70f, 340f, p)
        return bmp
    }

    fun pushTestBitmap(): String {
        return try {
            val svc = service() ?: return "service null"
            val m = findMethod(svc.javaClass, "uploadBitmap") ?: return "uploadBitmap missing"
            val id = m.invoke(svc, testBitmap())
            Log.i(TAG, "uploadBitmap -> " + id)
            "uploadBitmap OK: " + id
        } catch (t: Throwable) {
            val s = "uploadBitmap failed: " + t.javaClass.simpleName + ": " + t.message
            Log.w(TAG, s)
            s
        }
    }

    fun enumNames(): String {
        return try {
            val ec = Class.forName("gm.cluster.ScreenIdEnum").enumConstants
            ec.joinToString(",") { (it as Enum<*>).name }
        } catch (t: Throwable) { "enum unavailable: " + t.javaClass.simpleName }
    }

    fun showScreen(name: String): String {
        return try {
            val svc = service() ?: return "service null"
            val enumCls = Class.forName("gm.cluster.ScreenIdEnum")
            val constants = enumCls.enumConstants ?: emptyArray()
            if (constants.isEmpty()) return "enum empty"
            var value: Any? = null
            for (e in constants) {
                if ((e as Enum<*>).name == name) { value = e; break }
            }
            if (value == null) value = constants[0]
            var target: Method? = null
            for (m in svc.javaClass.methods) {
                if ((m.name == "displayScreen" || m.name == "showScreen") &&
                    m.parameterTypes.size == 1 && m.parameterTypes[0] == enumCls) { target = m; break }
            }
            if (target == null) return "displayScreen(ScreenIdEnum) missing"
            target.invoke(svc, value)
            Log.i(TAG, "displayScreen(" + (value as Enum<*>).name + ") OK")
            "displayScreen(" + (value as Enum<*>).name + ") OK"
        } catch (t: Throwable) {
            val s = "displayScreen failed: " + t.javaClass.simpleName + ": " + t.message
            Log.w(TAG, s)
            s
        }
    }
}
