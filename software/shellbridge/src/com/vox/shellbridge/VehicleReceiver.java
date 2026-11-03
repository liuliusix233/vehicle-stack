package com.vox.shellbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class VehicleReceiver extends BroadcastReceiver {
    private static final String TAG = "ShellBridge";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Vehicle broadcast: " + action);

        if ("patac.vehicle.ignition.onchange".equals(action)) {
            int state = intent.getIntExtra("state", -1);
            ShellService.lastIgnitionState = state;
            ShellService.lastVehicleUpdate = System.currentTimeMillis();
            Log.i(TAG, "  Ignition state: " + state + " (0=OFF, 1=ON)");

            // If ignition ON, start ShellService
            if (state == 1) {
                Log.i(TAG, "Ignition ON, starting ShellService");
                Intent svc = new Intent(context, ShellService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            }
        } else if ("patac.drivemode.onchange".equals(action)) {
            int mode = intent.getIntExtra("mode", -1);
            ShellService.lastDriveMode = mode;
            ShellService.lastVehicleUpdate = System.currentTimeMillis();
            String modeStr;
            switch (mode) {
                case 0: modeStr = "P (Park)"; break;
                case 1: modeStr = "R (Reverse)"; break;
                case 2: modeStr = "N (Neutral)"; break;
                case 3: modeStr = "D (Drive)"; break;
                default: modeStr = "Unknown (" + mode + ")";
            }
            Log.i(TAG, "  Drive mode: " + modeStr);
        }

        // Dump all extras for discovery
        if (intent.getExtras() != null) {
            for (String key : intent.getExtras().keySet()) {
                Object val = intent.getExtras().get(key);
                Log.i(TAG, "  Extra: " + key + " = " + val);
            }
        }
    }
}
