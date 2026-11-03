package com.vox.castsink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 仪表屏左侧选项卡注册表：index ↔ 名称 + 当前 tab 共享状态。
 * 数据链路（Telemetry/DashboardView 实例）与 tab 切换完全解耦：
 * 切页只改本对象 index + 内容槽 visibility，不 recreate、不重建视图。
 */
object ClusterTabs {
    /** 主屏/方向盘/EnvServer 注入切页用的广播 action，extra "index" int */
    const val ACTION = "com.vox.castsink.TAB"
    const val EXTRA_INDEX = "index"

    const val CLASSIC = 0     // 经典仪表（现有 DashboardView）
    const val ASSIST = 1      // 驾驶辅助
    const val SPEED = 2       // 速度视图
    const val MAP = 3         // 地图视图
    const val FACTORY = 4     // 原厂仪表（退出本 Activity，交还原厂）

    val NAMES = listOf("经典仪表", "驾驶辅助", "速度视图", "地图视图", "原厂仪表")

    @Volatile var index = CLASSIC
    fun clamp(i: Int): Int = i.coerceIn(0, NAMES.size - 1)

    // 配色沿用 DashboardView 黑底 + 冷色/红强调
    val BG = 0xFF06070A.toInt()
    val NAV_BG = 0xFF0B0E14.toInt()
    val INK = 0xFFF2EEE3.toInt()
    val MUTED = 0xFF8A8478.toInt()
    val RULE = 0xFF2E2B26.toInt()
    val RED = 0xFFE23A2E.toInt()
    val BLUE = 0xFF2F6BDB.toInt()
    val GREEN = 0xFF3E8E5A.toInt()
    val ACCENT_BG = 0xFF16233C.toInt()
}

/** 左侧竖排 nav：5 项等高，高亮当前项；支持触摸（有触摸时）点击。 */
class NavView(ctx: Context) : LinearLayout(ctx) {

    var onSelect: ((Int) -> Unit)? = null
    private val items = ArrayList<TextView>()

    init {
        orientation = VERTICAL
        setBackgroundColor(ClusterTabs.NAV_BG)
        for (i in ClusterTabs.NAMES.indices) {
            val tv = TextView(ctx)
            tv.text = ClusterTabs.NAMES[i]
            tv.gravity = Gravity.CENTER
            tv.textSize = 16f
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(ClusterTabs.MUTED)
            tv.setPadding(0, 24, 0, 24)
            tv.isClickable = true
            tv.setOnClickListener { onSelect?.invoke(i) }
            items.add(tv)
            addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        select(ClusterTabs.index)
    }

    fun select(i: Int) {
        val idx = ClusterTabs.clamp(i)
        for ((k, tv) in items.withIndex()) {
            val on = k == idx
            tv.setTextColor(if (on) Color.WHITE else ClusterTabs.MUTED)
            tv.setBackgroundColor(if (on) ClusterTabs.ACCENT_BG else Color.TRANSPARENT)
        }
    }
}

/**
 * 非仪表页的极简占位面板：标题 + 大号车速 + RPM/挡位/电压/连接状态。
 * 全部读真实数据（VehicleState/Telemetry），500ms 自刷新。
 */
class SimplePanelView(ctx: Context, private val title: String) : View(ctx) {

    private val t = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        t.textAlign = Paint.Align.LEFT
        t.color = ClusterTabs.BG
        canvas.drawRect(0f, 0f, w, h, t)
        // 标题（nav 高亮 + 页面标题双确认）
        t.color = ClusterTabs.BLUE
        t.textSize = 44f
        t.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(title, 48f, 100f, t)
        t.color = ClusterTabs.RULE
        canvas.drawRect(48f, 118f, 48f + t.measureText(title), 121f, t)
        // 大号车速
        val sp = VehicleState.speed
        val spTxt = if (sp >= 0f) String.format("%.0f", sp) else "--"
        t.textAlign = Paint.Align.CENTER
        t.color = if (sp >= 0f) ClusterTabs.RED else ClusterTabs.MUTED
        t.textSize = minOf(h * 0.34f, w * 0.3f)
        canvas.drawText(spTxt, w / 2f, h * 0.56f, t)
        t.textSize = 30f
        t.color = ClusterTabs.MUTED
        canvas.drawText("km/h", w / 2f, h * 0.64f, t)
        // 状态行：RPM / 挡位 / 12V / 数据链路
        val now = System.currentTimeMillis()
        val fresh = Telemetry.isFresh(now)
        val row = "RPM " + (if (VehicleState.rpm >= 0f) String.format("%.0f", VehicleState.rpm) else "--") +
            "   GEAR " + VehicleState.gear +
            "   12V " + (if (VehicleState.volt12 >= 0f) String.format("%.1f", VehicleState.volt12) else "--") +
            "   LINK " + (if (fresh) "ONLINE " + String.format("%.1f", Telemetry.hz) + "Hz" else "OFFLINE")
        t.textSize = 34f
        t.color = if (fresh) ClusterTabs.GREEN else ClusterTabs.MUTED
        canvas.drawText(row, w / 2f, h - 60f, t)
        t.textAlign = Paint.Align.LEFT
        postInvalidateDelayed(500L)
    }
}
