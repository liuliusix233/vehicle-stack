package com.vox.castsink;

import java.util.ArrayList;
import java.util.List;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.graphics.Point;

/**
 * 多屏发现与拉起。
 * 对标凯迪拉克：
 * - DualDisplayCastPolicy(max=2)：主屏+仪表双会话上限 → M1 常量镜像
 * - CarlinkClusterActivity 的 display id 运行时经 View.getDisplay().getDisplayId() 获取，
 *   代码不假设固定 id → 本类同样运行时枚举
 * - am start --display 可行性：dex 无硬编码，Activity 由外部拉起 → 提供命令字符串给 ShellBridge
 */
public final class ClusterDisplayHelper {

    private static final String TAG = "ClusterDisplayHelper";

    /** 对标 DualDisplayCastPolicy hardUpperBound=2（clamp ≥1）。 */
    public static final int MAX_CONCURRENT_CAST_SESSIONS = 2;

    public static final String COMPONENT = "com.vox.castsink/.ClusterCastActivity";

    private ClusterDisplayHelper() { }

    /** 枚举 PRESENTATION 类别显示屏（仪表/副屏），并打日志。 */
    public static List<Display> listPresentationDisplays(Context ctx) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        List<Display> out = new ArrayList<>();
        if (dm == null) {
            Log.w(TAG, "DisplayManager null");
            return out;
        }
        for (Display d : dm.getDisplays()) {
            Log.i(TAG, "display: " + describeDisplay(d));
        }
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            out.add(d);
        }
        Log.i(TAG, "presentation displays: " + out.size()
                + ", maxConcurrentCastSessions=" + MAX_CONCURRENT_CAST_SESSIONS);
        return out;
    }

    /** displayId/名称/分辨率 一行描述（对标 CarlinkClusterActivity 日志格式）。 */
    public static String describeDisplay(Display d) {
        Point size = new Point();
        d.getRealSize(size);
        return "displayId=" + d.getDisplayId()
                + " name=" + d.getName()
                + " size=" + size.x + "x" + size.y
                + " state=" + d.getState();
    }

    /**
     * 生成 ShellBridge 可执行命令字符串（不实际执行）。
     * 对标 dex 结论：Activity 由外部拉起，am start --display 可行。
     */
    public static String buildAmStartCommand(int displayId) {
        return "am start --display " + displayId + " -n " + COMPONENT;
    }

    /**
     * 方式 a) ActivityOptions.setLaunchDisplayId + startActivity。
     * 失败（跨 display 启动被系统拒绝等）则捕获异常并记录，返回 false。
     */
    public static boolean launchOnDisplay(Context ctx, int displayId) {
        try {
            Intent i = new Intent(ctx, ClusterCastActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
            Log.i(TAG, "launched ClusterCastActivity on display " + displayId);
            return true;
        } catch (Exception e) { // 含 ActivityNotStartableException / SecurityException
            Log.e(TAG, "launchOnDisplay(" + displayId + ") failed", e);
            return false;
        }
    }
}
