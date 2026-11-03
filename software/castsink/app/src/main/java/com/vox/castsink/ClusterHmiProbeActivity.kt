package com.vox.castsink

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 只读优先的 ClusterHMI 探针：
 *  - PROBE：枚举 gm.cluster.ClusterHmiManager / IClusterHmi 方法 + 读状态，结果写 /sdcard/Download/hmi_probe.txt
 *  - OVERLAY：一次 displayOverlay 测试，5 秒后自动 cancelOverlay（失败只记录，不重试）
 *  - CANCEL：立即 cancelOverlay
 */
class ClusterHmiProbeActivity : Activity() {

    private val sb = StringBuilder()
    private lateinit var tv: TextView
    private val TAG = "ClusterHmiProbeAct"
    private val OVERLAY_ID = "voxprobe"

    private fun log(line: String) {
        sb.append(line).append('\n')
        Log.i(TAG, line)
        runOnUiThread { tv.text = sb.toString() }
    }

    private fun save() {
        val txt = sb.toString()
        try { File("/sdcard/Download/hmi_probe.txt").writeText(txt) } catch (_: Throwable) { }
        try {
            val d = getExternalFilesDir(null) ?: filesDir
            File(d, "hmi_probe.txt").writeText(txt)
        } catch (_: Throwable) { }
    }

    private fun manager(): Any? {
        val cls = Class.forName("gm.cluster.ClusterHmiManager")
        return cls.getMethod("getInstance").invoke(null)
    }

    private fun svc(): Any? {
        val inst = manager() ?: return null
        // getService() 是 private：先走 declared method
        for (n in listOf("getService", "getClusterHmiService", "getIClusterHmi")) {
            try {
                val m = inst.javaClass.getDeclaredMethod(n)
                m.isAccessible = true
                return m.invoke(inst)
            } catch (_: Throwable) { }
        }
        // 兜底：直接读 clusterHmiServiceProvider 字段，再调它的 getService()
        try {
            val f = inst.javaClass.getDeclaredField("clusterHmiServiceProvider")
            f.isAccessible = true
            val provider = f.get(inst) ?: return null
            val m = provider.javaClass.getMethod("getService")
            return m.invoke(provider)
        } catch (_: Throwable) { }
        return null
    }

    private fun call(obj: Any?, name: String, vararg args: Any?): Any? {
        if (obj == null) return "obj=null"
        return try {
            val m = obj.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
                ?: return "no method $name/${args.size}"
            m.invoke(obj, *args)
        } catch (t: Throwable) {
            "EX " + t.javaClass.simpleName + ": " + t.message
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF101014.toInt())
        tv = TextView(this)
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 11f
        val scroll = ScrollView(this)
        scroll.addView(tv)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(Button(this).apply { text = "PROBE"; setOnClickListener { probe() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "OVERLAY 5s"; setOnClickListener { overlay() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "CANCEL"; setOnClickListener { cancel() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        setContentView(root)
        probe()
    }

    private fun probe() {
        sb.setLength(0)
        log("-- ClusterHMI probe --")
        log("lib info3_cluster: " + try { Class.forName("gm.cluster.ClusterHmiManager"); "OK" } catch (t: Throwable) { "FAIL " + t.javaClass.simpleName })
        val s = try { svc() } catch (t: Throwable) { null }
        log("service = " + s)
        if (s == null) { save(); return }
        val noArg = listOf("isClusterServiceReady", "getDisplayInfos", "getClusterConfig", "getHudConfig", "isClusterInGuageView", "isClusterInMinimalistMode")
        for (n in noArg) log(n + " -> " + call(s, n))
        log("isHudOrOverlay(voxprobe) -> " + call(s, "isHudOrOverlay", OVERLAY_ID))
        log("getCurrentScreenId(0) -> " + call(s, "getCurrentScreenId", 0))
        log("getDefaultScreenId(0) -> " + call(s, "getDefaultScreenId", 0))
        log("-- IClusterHmi methods --")
        try {
            s.javaClass.methods.map { it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" }.distinct().sorted().forEach { log(it) }
        } catch (t: Throwable) { log("methods EX " + t) }
        log("ScreenType values -> " + try { Class.forName("gm.cluster.widget.ScreenType").enumConstants?.joinToString(",") } catch (t: Throwable) { "EX" })
        log("-- constants --")
        for (f in listOf("EXTENDED_ASSET_ROOT", "TRANSIENT_ASSET_ROOT", "ACTION_CLUSTER_THEME_CHANGED")) {
            try { log(f + " = " + Class.forName("gm.cluster.ClusterHmiManager").getField(f).get(null)) } catch (t: Throwable) { log(f + " EX") }
        }
        save()
    }

    private fun overlay() {
        val s = try { svc() } catch (t: Throwable) { null }
        if (s == null) { log("OVERLAY: service null"); save(); return }
        val rootName = try { Class.forName("gm.cluster.ClusterHmiManager").getField("EXTENDED_ASSET_ROOT").get(null) } catch (t: Throwable) { "Extended/" }
        val sts = try { Class.forName("gm.cluster.widget.ScreenType").enumConstants } catch (t: Throwable) { null }
        log("ScreenType values=" + (sts?.joinToString(",") ?: "null"))
        val st = sts?.firstOrNull()
        for (root in listOf(rootName, "Extended/themenone.kzb")) {
            val r = call(s, "displayOverlay", OVERLAY_ID, root, 0, 0, st, null, null)
            log("displayOverlay(root=" + root + ") -> " + r)
        }
        save()
        Handler(Looper.getMainLooper()).postDelayed({ cancel() }, 5000L)
    }

    private fun cancel() {
        val s = try { svc() } catch (t: Throwable) { null }
        if (s == null) { log("CANCEL: service null"); return }
        val r = call(s, "cancelOverlay", OVERLAY_ID)
        log("cancelOverlay -> " + r)
        save()
    }
}
