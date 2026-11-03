package com.vox.shellbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ShellBridge";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Boot completed, starting service");
        Intent svc = new Intent(context, ShellService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
