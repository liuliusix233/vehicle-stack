package com.vox.castsink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display

object ClusterState {
    @Volatile var visible = false
}

/** 开机 / 点火：拉起前台守护。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val i = Intent(context, AutoStartService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            // 合并版：开机同时拉起 8888 命令服务与公网反向隧道
            val svc = Intent(context, ShellService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
            RelayTunnel.start(context.applicationContext)
            Log.i("BootReceiver", "started by " + intent?.action)
        } catch (t: Throwable) {
            Log.w("BootReceiver", "" + t.message)
        }
    }
}

/**
 * 仪表屏自动启动：
 *  - 监听 display 2 由灭转亮（点火/唤醒）
 *  - 开机 4s 后补一次
 *  - Activity 已在屏上（ClusterState.visible）时不重复拉起
 */
class AutoStartService : Service() {

    private lateinit var dm: DisplayManager
    private var lastLaunch = 0L
    private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private var startAt = 0L

    override fun onCreate() {
        super.onCreate()
        dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        try {
            val svc = Intent(this, ShellService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        } catch (_: Throwable) { }
        RelayTunnel.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        if (!started) {
            started = true
            startAt = System.currentTimeMillis()
            dm.registerDisplayListener(listener, null)
            handler.postDelayed({ tryLaunch("boot") }, 4000L)
            // 点火/开机后前 10 分钟每 20s 重试（车机可能先出桌面再亮副屏），之后每 2 分钟兜底
            handler.postDelayed({ handler.post(ticker) }, 8000L)
        }
        // 低电压自杀后不要被系统重新拉起（否则又占屏耗电）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { dm.unregisterDisplayListener(listener) } catch (_: Throwable) { }
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tryLaunch("tick")
            val delay = if (System.currentTimeMillis() - startAt < 600000L) 20000L else 120000L
            handler.postDelayed(this, delay)
        }
    }

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { }
        override fun onDisplayRemoved(displayId: Int) { }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != 2) return
            val d = dm.getDisplay(2) ?: return
            if (d.state == Display.STATE_ON) tryLaunch("display-on")
        }
    }

    private fun tryLaunch(reason: String) {
        if (ClusterState.visible) return
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 15000L) return
        val d = dm.getDisplay(2) ?: return
        if (d.state != Display.STATE_ON) return
        lastLaunch = now
        Log.i("AutoStart", "launch reason=" + reason)
        ClusterDisplayHelper.launchOnDisplay(this, 2)
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("cluster", "VoxCluster", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "cluster") else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("VoxCluster")
            .setContentText("仪表盘自动启动守护")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
