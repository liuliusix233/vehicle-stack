package com.vox.shellbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String TAG = "ShellBridge-Main";

    private static final int REQUEST_PERMISSIONS = 100;

    // All dangerous permissions we need
    private static final String[] REQUIRED_PERMISSIONS;

    static {
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+ has BLUETOOTH_CONNECT, BLUETOOTH_SCAN
            REQUIRED_PERMISSIONS = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            };
        } else {
            REQUIRED_PERMISSIONS = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            };
        }
    }

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF1A1A2E);

        statusText = new TextView(this);
        statusText.setText("ShellBridge v2.0\n正在请求权限...");
        statusText.setTextSize(18);
        statusText.setGravity(Gravity.CENTER);
        statusText.setTextColor(0xFFAAAAAA);
        root.addView(statusText);

        setContentView(root);

        // Request permissions
        requestNeededPermissions();

        // Start the service regardless (it'll have limited functionality)
        startService(new Intent(this, ShellService.class));
    }

    private void requestNeededPermissions() {
        java.util.ArrayList<String> toRequest = new java.util.ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(perm);
            }
        }

        if (!toRequest.isEmpty()) {
            String[] arr = toRequest.toArray(new String[0]);
            Log.i(TAG, "Requesting " + arr.length + " permissions: " + java.util.Arrays.toString(arr));
            requestPermissions(arr, REQUEST_PERMISSIONS);
        } else {
            Log.i(TAG, "All permissions already granted!");
            statusText.setText("ShellBridge v2.0\n✅ 所有权限已授予\n端口 8888");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            StringBuilder sb = new StringBuilder("ShellBridge v2.0\n");
            int granted = 0, denied = 0;
            for (int i = 0; i < permissions.length; i++) {
                String status = (grantResults[i] == PackageManager.PERMISSION_GRANTED) ? "✅" : "❌";
                String shortName = permissions[i].substring(permissions[i].lastIndexOf('.') + 1);
                sb.append(status).append(" ").append(shortName).append("\n");
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) granted++;
                else denied++;
            }
            Log.i(TAG, "Permissions result: " + granted + " granted, " + denied + " denied");
            statusText.setText(sb.toString());

            // Schedule a self-finish so the user goes back to launcher
            // After showing the permission result for a bit
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Minimize rather than finish - keep the Service alive
                    moveTaskToBack(true);
                }
            }, 3000);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        moveTaskToBack(true);
    }

    /**
     * Check if a specific dangerous permission is granted.
     * Called from ShellService via a static helper.
     */
    public static boolean hasPermission(Activity activity, String permission) {
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
