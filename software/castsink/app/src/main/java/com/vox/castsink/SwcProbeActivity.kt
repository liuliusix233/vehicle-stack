package com.vox.castsink

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SWC 探针：验证方向盘上/下/确认键到底有没有以 KeyEvent 或广播形式送达第三方 App。
 * 结果写入 getExternalFilesDir/swc_probe.log
 */
class SwcProbeActivity : Activity() {

    private lateinit var tv: TextView
    private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val ACTIONS = arrayOf(
        "com.patac.swc.event",
        "patac.swc.event",
        "com.patac.swc.event.keycode",
        "patac.intent.swc",
        "com.gm.car.input.SWC",
        "com.patac.car.input.SWC",
        "android.intent.action.MEDIA_BUTTON",
        "gm.cluster.action.APP_SELECTED",
        "com.gm.cluster.action.APP_SELECTED",
        "gm.cluster.action.SERVICE_READY"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.setBackgroundColor(Color.BLACK)
        tv.textSize = 22f
        tv.setPadding(24, 24, 24, 24)
        tv.text = "SWC PROBE 启动中…"
        setContentView(tv)

        logFile = File(getExternalFilesDir(null) ?: filesDir, "swc_probe.log")
        log("=== PROBE START ===")

        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                log("BC action=" + (i?.action ?: "?") + " extras=" + (i?.extras?.toString() ?: "-"))
            }
        }
        val f = IntentFilter()
        for (a in ACTIONS) f.addAction(a)
        try {
            registerReceiver(rx, f)
            log("receiver registered actions=" + ACTIONS.size)
        } catch (t: Throwable) {
            log("REGISTER FAIL " + t)
        }

        Thread(Runnable {
            while (!isFinishing) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { }
                runOnUiThread(Runnable { tv.text = buildText() })
            }
        }).start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        log("KEYDOWN " + keyCode + " " + KeyEvent.keyCodeToString(keyCode))
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        log("KEYUP " + keyCode + " " + KeyEvent.keyCodeToString(keyCode))
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            log("DISPATCH " + event.keyCode + " src=" + event.source + " repeat=" + event.repeatCount)
        }
        return super.dispatchKeyEvent(event)
    }

    private fun log(s: String) {
        Log.i("SWCPROBE", s)
        try {
            val f = logFile ?: return
            f.appendText(fmt.format(Date()) + " " + s + "\n")
        } catch (t: Throwable) { }
    }

    private fun buildText(): String {
        val sb = StringBuilder("SWC PROBE\n\n")
        try {
            val f = logFile
            if (f != null && f.exists()) {
                val lines = f.readLines()
                val from = if (lines.size > 12) lines.size - 12 else 0
                for (i in from until lines.size) sb.append(lines[i]).append("\n")
            }
        } catch (t: Throwable) {
            sb.append("read err ").append(t)
        }
        return sb.toString()
    }
}
