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
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * 仪表屏专用 Activity（本地渲染版）：
 * 全屏自绘双圆表仪表盘，切到 display 2 后即"换上"基础仪表。
 */
class ClusterCastActivity : Activity() {

    companion object {
        private const val TAG = "ClusterCastActivity"
        const val ACTION_FINISH = "com.vox.castsink.FINISH_CLUSTER"
    }

    private var nav: NavView? = null
    private var content: FrameLayout? = null
    private var dashboard: DashboardView? = null
    private var tabReceiver: BroadcastReceiver? = null
    private var finishReceiver: BroadcastReceiver? = null

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    /** 切页：只切内容槽 visibility + nav 高亮，不重建任何视图/数据链路 */
    private fun showTab(i: Int) {
        val idx = ClusterTabs.clamp(i)
        if (idx == ClusterTabs.FACTORY) {
            try { finishAndRemoveTask() } catch (_: Throwable) { finish() }
            return
        }
        ClusterTabs.index = idx
        nav?.select(idx)
        content?.let { c ->
            for (k in 0 until c.childCount) {
                c.getChildAt(k).visibility = if (k == idx) View.VISIBLE else View.GONE
            }
        }
    }

    // 仪表屏无触摸时的硬件键导航：上下移动、中键/回车选中
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { showTab(ClusterTabs.index - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { showTab(ClusterTabs.index + 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showTab(ClusterTabs.index); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        ClusterState.visible = true
    }

    override fun onPause() {
        ClusterState.visible = false
        super.onPause()
    }

    override fun onDestroy() {
        ClusterState.visible = false
        tabReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) { } }
        tabReceiver = null
        finishReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) { } }
        finishReceiver = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 色彩深度：强制 RGBA_8888（默认可能是 RGB_565 → 渐变色带）
        try {
            window.setFormat(android.graphics.PixelFormat.RGBA_8888)
        } catch (_: Throwable) { }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        // 透明通道：窗口本体不铺底色，交给 DashboardView 自绘半透明层
        try {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        } catch (_: Throwable) { }

        // 权限弹窗必须在主屏（可触摸）请求；仪表屏（display 2）无触摸，弹了也点不到
        Telemetry.start()
        try { startService(Intent(this, AutoStartService::class.java)) } catch (_: Throwable) { }
        // 左侧竖排选项卡 + 右侧内容槽：整块仪表画布自绘（仿原厂布局）
        val root = LinearLayout(this)
        root.orientation = LinearLayout.HORIZONTAL
        root.setBackgroundColor(ClusterTabs.BG)
        val navView = NavView(this)
        nav = navView
        root.addView(navView, LinearLayout.LayoutParams(dp(240), -1))
        val slot = FrameLayout(this)
        slot.setBackgroundColor(ClusterTabs.BG)
        content = slot
        dashboard = DashboardView(this)   // 经典仪表：原实例常驻，切回保留动画状态
        slot.addView(dashboard, FrameLayout.LayoutParams(-1, -1))
        for (i in 1..3) {
            slot.addView(SimplePanelView(this, ClusterTabs.NAMES[i]), FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(slot, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        navView.onSelect = { i -> showTab(i) }
        showTab(ClusterTabs.index)
        // 主屏/方向盘/EnvServer 注入切页的广播入口
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                showTab(i?.getIntExtra(ClusterTabs.EXTRA_INDEX, ClusterTabs.index) ?: ClusterTabs.index)
            }
        }
        try { registerReceiver(r, IntentFilter(ClusterTabs.ACTION)) } catch (_: Throwable) { }
        tabReceiver = r
        // 合并后的 #CLUSTER STOP 入口
        val fr = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                try { finishAndRemoveTask() } catch (_: Throwable) { finish() }
            }
        }
        try { registerReceiver(fr, IntentFilter(ACTION_FINISH)) } catch (_: Throwable) { }
        finishReceiver = fr

        root.post {
            Log.i(TAG, "dashboard on display=" + windowManager.defaultDisplay.displayId +
                " size=" + root.width + "x" + root.height)
            EnvServer.clusterViewSize = root.width.toString() + "x" + root.height
        }

        // 熄火/亏电保护：<12.9V 连续 5s → 关投屏并彻底退出
        val guard = object : Runnable {
            override fun run() {
                if (PowerGuard.check(System.currentTimeMillis(), VehicleState.volt12, VehicleState.rpm, VehicleState.speed)) {
                    Log.i(TAG, "low voltage shutdown volt=" + VehicleState.volt12)
                    stopService(Intent(this@ClusterCastActivity, AutoStartService::class.java))
                    try { finishAndRemoveTask() } catch (_: Throwable) { finish() }
                    android.os.Handler(mainLooper).postDelayed({
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, 400L)
                    return
                }
                android.os.Handler(mainLooper).postDelayed(this, 1000L)
            }
        }
        android.os.Handler(mainLooper).postDelayed(guard, 1000L)
    }
}
