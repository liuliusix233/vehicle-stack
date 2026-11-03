package com.vox.castsink

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

private const val TAG = "ClusterDisplayHelper"

/**
 * 多屏策略：枚举 Presentation 屏 + 拉起策略。
 * 借鉴：DualDisplayCastPolicy 的目标屏选择（主屏/仪表屏路由）。
 * OEM 上 setLaunchDisplayId 可能抛 SecurityException（需 INTERNAL_SYSTEM_WINDOW），
 * 此时回退给出 ShellBridge 可执行的 am 命令（见 permissions-notes.md）。
 */
object ClusterDisplayHelper {

    fun presentationDisplays(context: Context): List<Display> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()
    }

    /** 枚举全部 display + presentation 子集，附 state，同时打 logcat。 */
    fun dumpDisplays(context: Context): String {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val all = dm.getDisplays()
        val pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val sb = StringBuilder("all=${all.size} presentation=${pres.size}")
        for (d in all) {
            val line = "  id=${d.displayId} name=${d.name} ${d.width}x${d.height} state=${d.state}"
            Log.i(TAG, line)
            sb.append('\n').append(line)
        }
        return sb.toString()
    }

    /** 指定 displayId 强行拉起（逐个试，异常原样回报）。 */
    fun launchOnDisplay(context: Context, displayId: Int): String {
        return try {
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)
            val i = Intent(context, ClusterCastActivity::class.java)
            // NEW_TASK + CLEAR_TASK：重复点图标只保留一个仪表实例，不叠栈
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(i, options.toBundle())
            Log.i(TAG, "launched on display $displayId")
            "display $displayId: startActivity OK"
        } catch (t: Throwable) {
            Log.w(TAG, "launch on $displayId failed", t)
            "display $displayId: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 静默版：成功 true / 失败 false（供开机自动拉起用） */
    fun launchOnDisplayOk(context: Context, displayId: Int): Boolean = try {
        val options = ActivityOptions.makeBasic()
        options.setLaunchDisplayId(displayId)
        val i = Intent(context, ClusterCastActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(i, options.toBundle())
        true
    } catch (t: Throwable) {
        Log.w(TAG, "auto launch on $displayId failed", t)
        false
    }

    /** 尝试在第一个 Presentation 屏拉起 ClusterCastActivity，返回结果描述。 */
    fun launchCluster(context: Context): String {
        val list = presentationDisplays(context)
        if (list.isEmpty()) return "No presentation display (未找到仪表屏)"
        val target = list.first()
        return try {
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(target.displayId)
            context.startActivity(Intent(context, ClusterCastActivity::class.java), options.toBundle())
            "Launched ClusterCastActivity on display ${target.displayId} (${target.name})"
        } catch (e: SecurityException) {
            val cmd = buildAmStartCommand(target.displayId)
            Log.w(TAG, "startActivity denied: ${e.message}; fallback -> $cmd")
            "SecurityException: ${e.message}\nShellBridge 回退: $cmd"
        }
    }

    /** 供 ShellBridge 执行的 am 命令（本函数只生成字符串，不执行）。 */
    fun buildAmStartCommand(displayId: Int): String =
        "am start --display $displayId -n com.vox.castsink/.ClusterCastActivity"
}
