package com.vox.castsink

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * M1 主界面：SurfaceView 预览 + 状态文本 + 仪表屏按钮。
 * 借鉴：NativeWfdSink 的预览窗口 + CarlinkClusterActivity 的入口逻辑。
 * 布局纯代码构建，避免依赖 R.java（本机无 javac，见 build.sh）。
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val TAG = "CastSink"
        const val PORT = 27183
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private var decoder: H264Decoder? = null
    private var receiver: H264TcpReceiver? = null
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 12 蓝牙权限：只在主屏 Activity 里请求，用户才点得到
        // v72：定位权限不再跟 SDK>=31 绑定（Android 10/11 车机上原来根本不会请求 → GPS 全程静默失败）
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val perms = ArrayList<String>()
            if (android.os.Build.VERSION.SDK_INT >= 31) perms.add("android.permission.BLUETOOTH_CONNECT")
            perms.add("android.permission.ACCESS_FINE_LOCATION")
            perms.add("android.permission.ACCESS_COARSE_LOCATION")
            // v133: 车机原生车速（dangerous，需运行时授权）
            perms.add("android.car.permission.CAR_SPEED")
            perms.add("android.car.permission.CAR_ENERGY")
            perms.add("android.permission.RECORD_AUDIO")
            perms.add("android.permission.CAMERA")
            perms.add("android.permission.READ_EXTERNAL_STORAGE")
            perms.add("android.permission.WRITE_EXTERNAL_STORAGE")
            val need = ArrayList<String>()
            for (pm in perms) {
                if (checkSelfPermission(pm) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(pm)
            }
            if (need.size > 0) {
                try { requestPermissions(need.toTypedArray(), 1001) } catch (_: Throwable) { }
            }
        }
        // 透明浮窗：无白底、无标题栏、全屏沉浸
        try {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } catch (_: Throwable) { }

        val root = FrameLayout(this)
        root.setBackgroundColor(0x00000000)

        // 预览面留 1x1（H264 管线继续工作，但不再占屏、不挡车机界面）
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1))

        // 小浮按钮（不占横向）：wrap_content 贴右下角
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.gravity = Gravity.CENTER_HORIZONTAL
        bar.setPadding(dp(14), dp(12), dp(14), dp(14))
        bar.background = rounded(0xD90B0E14.toInt(), dp(16).toFloat())

        statusText = TextView(this)
        statusText.setTextColor(0xFF8A8478.toInt())
        statusText.textSize = 11f
        statusText.text = "CastSink · :$PORT"
        val sp = LinearLayout.LayoutParams(-2, -2)
        sp.bottomMargin = dp(8)
        bar.addView(statusText, sp)

        val btnMotor = Button(this)
        btnMotor.text = if (MotorSound.isOn) "电机声：开" else "电机声：关"
        btnMotor.textSize = 15f
        btnMotor.setTextColor(0xFF0B0C0F.toInt())
        btnMotor.background = rounded(if (MotorSound.isOn) 0xFF39D353.toInt() else 0xFF3A3F4B.toInt(), dp(12).toFloat())
        btnMotor.setOnClickListener {
            val on = MotorSound.toggle(this)
            btnMotor.text = if (on) "电机声：开" else "电机声：关"
            btnMotor.setTextColor(if (on) 0xFF0B0C0F.toInt() else 0xFFE6E2D8.toInt())
            btnMotor.background = rounded(if (on) 0xFF39D353.toInt() else 0xFF3A3F4B.toInt(), dp(12).toFloat())
        }
        val mpp = LinearLayout.LayoutParams(dp(168), dp(52))
        mpp.topMargin = dp(10)
        bar.addView(btnMotor, mpp)

        val volRow = LinearLayout(this)
        volRow.orientation = LinearLayout.HORIZONTAL
        volRow.gravity = Gravity.CENTER
        val btnDown = Button(this)
        btnDown.text = "音量 -"
        btnDown.textSize = 13f
        btnDown.setTextColor(0xFF0B0C0F.toInt())
        btnDown.background = rounded(0xFFD9D4C7.toInt(), dp(10).toFloat())
        btnDown.setOnClickListener {
            MotorSound.gain = (MotorSound.gain - 0.05f).coerceAtLeast(0.05f)
            statusText.text = "音量 " + Math.round(MotorSound.gain * 100) + "%"
        }
        val btnUp = Button(this)
        btnUp.text = "音量 +"
        btnUp.textSize = 13f
        btnUp.setTextColor(0xFF0B0C0F.toInt())
        btnUp.background = rounded(0xFFD9D4C7.toInt(), dp(10).toFloat())
        btnUp.setOnClickListener {
            MotorSound.gain = (MotorSound.gain + 0.05f).coerceAtMost(1.0f)
            statusText.text = "音量 " + Math.round(MotorSound.gain * 100) + "%"
        }
        val vp1 = LinearLayout.LayoutParams(dp(80), dp(44))
        val vp2 = LinearLayout.LayoutParams(dp(80), dp(44))
        vp2.leftMargin = dp(8)
        volRow.addView(btnDown, vp1)
        volRow.addView(btnUp, vp2)
        val vrp = LinearLayout.LayoutParams(-2, -2)
        vrp.topMargin = dp(8)
        bar.addView(volRow, vrp)

        val btnLaunch = Button(this)
        btnLaunch.text = "拉起第二屏"
        btnLaunch.textSize = 16f
        btnLaunch.setTextColor(0xFF0B0C0F.toInt())
        btnLaunch.background = rounded(0xFFF2B705.toInt(), dp(12).toFloat())
        btnLaunch.setOnClickListener {
            if (ClusterDisplayHelper.launchOnDisplayOk(this, 2)) goHome()
            else statusText.text = ClusterDisplayHelper.launchOnDisplay(this, 2)
        }
        bar.addView(btnLaunch, LinearLayout.LayoutParams(dp(168), dp(56)))

        val btnProbe = Button(this)
        btnProbe.text = "HMI探针"
        btnProbe.textSize = 16f
        btnProbe.setOnClickListener {
            startActivity(android.content.Intent(this, ClusterHmiProbeActivity::class.java))
        }
        bar.addView(btnProbe, LinearLayout.LayoutParams(dp(140), dp(56)))

        // 兜底按钮：贴左下角；默认隐藏，只有自动拉起失败才露出来
        val bp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        bp.setMargins(dp(24), 0, 0, dp(28))
        root.addView(bar, bp)
        bar.visibility = View.VISIBLE

        setContentView(root)

        // 实时状态条：48V 功率 / SOC / 电机声状态
        val tick = object : Runnable {
            override fun run() {
                val p = VehicleState.hybPowerW
                val pw = if (p < -9000f) "--" else String.format(java.util.Locale.US, "%.0f", p)
                val socTxt = if (VehicleState.soc >= 0f) String.format(java.util.Locale.US, "%.1f", VehicleState.soc) else "--"
                statusText.text = "48V " + pw + "W · SOC " + socTxt + "% · 电机声 " + (if (MotorSound.isOn) "ON" else "OFF")
                android.os.Handler(mainLooper).postDelayed(this, 700L)
            }
        }
        android.os.Handler(mainLooper).postDelayed(tick, 700L)

        EnvServer.start(applicationContext)
        startBridge()

        // 点图标 = 直接在 display 2 拉起仪表，然后主屏退回桌面（无二级菜单）
        var tries = 0
        val boot = object : Runnable {
            override fun run() {
                tries++
                if (ClusterDisplayHelper.launchOnDisplayOk(this@MainActivity, 2)) {
                    bar.visibility = View.VISIBLE
                    statusText.text = "仪表屏已拉起 · 控制台常驻"
                } else if (tries < 3) {
                    root.postDelayed(this, 1200)
                } else {
                    bar.visibility = View.VISIBLE
                    statusText.text = "display 2 拉起失败，点按钮重试"
                }
            }
        }
        root.postDelayed(boot, 400)

        // 熄火/亏电保护：<12.9V 连续 5s → 彻底退出
        val guard = object : Runnable {
            override fun run() {
                if (PowerGuard.check(System.currentTimeMillis(), VehicleState.volt12, VehicleState.rpm, VehicleState.speed)) {
                    try { stopService(Intent(this@MainActivity, AutoStartService::class.java)) } catch (_: Throwable) { }
                    finish()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return
                }
                android.os.Handler(mainLooper).postDelayed(this, 1000L)
            }
        }
        android.os.Handler(mainLooper).postDelayed(guard, 1000L)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startPipeline(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopPipeline()
    }

    override fun onResume() {
        super.onResume()
        if (surfaceReady) startPipeline(surfaceView.holder)
    }

    override fun onPause() {
        super.onPause()
        stopPipeline()
    }

    private fun startPipeline(holder: SurfaceHolder) {
        if (receiver != null) return
        val d = H264Decoder(holder.surface)
        d.start()
        decoder = d
        receiver = H264TcpReceiver(PORT) { frame -> d.feed(frame) }.also { it.start() }
        statusText.text = "Listening on :$PORT"
    }

    /** 合并版：ShellBridge 的 8888 命令服务 + 反向隧道随主界面一起拉起 */
    private fun startBridge() {
        try {
            val i = Intent(this, ShellService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "ShellService start: " + t.message)
        }
        RelayTunnel.start(applicationContext)
    }

    private fun goHome() {
        try {
            val home = Intent(Intent.ACTION_MAIN)
            home.addCategory(Intent.CATEGORY_HOME)
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(home)
        } catch (_: Throwable) { }
        moveTaskToBack(true)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun stopPipeline() {
        receiver?.stop()
        receiver = null
        decoder?.release()
        decoder = null
    }
}
