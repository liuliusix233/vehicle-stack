package com.vox.shellbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import java.util.List;
import android.Manifest;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ShellService extends Service {
    private static final String TAG = "ShellBridge";
    private static final int PORT = 8888;
    private static final int NOTIFICATION_ID = 1;

    // Vehicle state (updated by VehicleReceiver)
    public static volatile int lastIgnitionState = -1;     // -1=unknown, 0=OFF, 1=ON
    public static volatile int lastDriveMode = -1;          // -1=unknown, 0=P, 1=R, 2=N, 3=D
    public static volatile long lastVehicleUpdate = 0;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running = true;

    @Override
    public void onCreate() {
        super.onCreate();
        threadPool = Executors.newCachedThreadPool();

        tryUpgradeToForeground();

        threadPool.submit(new ListenLoop());

        Log.i(TAG, "Service started, port=" + PORT);
        Log.i(TAG, "FilesDir: " + getFilesDir());
        Log.i(TAG, "CacheDir: " + getCacheDir());
    }

    private void tryUpgradeToForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "shell_bridge", "ShellBridge",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("ShellBridge");
                channel.setShowBadge(false);
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.createNotificationChannel(channel);

                Notification notification = new Notification.Builder(this, "shell_bridge")
                        .setContentTitle("ShellBridge v2.4")
                        .setContentText("v2.7 端口 " + PORT + " 已就绪")
                        .setSmallIcon(android.R.drawable.ic_menu_manage)
                        .setOngoing(true)
                        .build();

                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.w(TAG, "Foreground upgrade failed", e);
        }
    }

    private class ListenLoop implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    if (serverSocket == null || serverSocket.isClosed()) {
                        serverSocket = new ServerSocket(PORT);
                        Log.i(TAG, "Listening on 0.0.0.0:" + PORT);
                    }

                    Socket client = serverSocket.accept();
                    Log.i(TAG, "Client connected: " + client.getInetAddress().getHostAddress());
                    threadPool.submit(new ClientHandler(client));

                } catch (IOException e) {
                    if (running) {
                        Log.e(TAG, "Socket error, will retry in 2s", e);
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    }
                }
            }
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket client;

        ClientHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                client.setSoTimeout(3000);
                InputStream netInRaw = client.getInputStream();
                BufferedReader netReader = new BufferedReader(new InputStreamReader(netInRaw));
                String firstLine = netReader.readLine();
                client.setSoTimeout(0);

                if (firstLine == null) {
                    client.close();
                    return;
                }

                String cmd = firstLine.trim().toUpperCase();

                if (cmd.equals("#SCAN")) {
                    Log.i(TAG, "Command: SCAN");
                    OutputStream netOut = client.getOutputStream();
                    netOut.write(("CarScanner v3 running...\n\n").getBytes());
                    netOut.flush();

                    new CarScanner(getContentResolver(), ShellService.this).scan();

                    // Read from file (may fail, but the scanner also has callback now)
                    StringBuilder result = new StringBuilder();
                    try {
                        File f = new File("/sdcard/Download/car_scan.txt");
                        if (f.exists()) {
                            BufferedReader fr = new BufferedReader(new java.io.FileReader(f));
                            String line;
                            while ((line = fr.readLine()) != null) {
                                result.append(line).append("\n");
                            }
                            fr.close();
                        } else {
                            result.append("(scan file not found)\n");
                        }
                    } catch (Exception e) {
                        result.append("(read error: ").append(e).append(")\n");
                    }

                    netOut.write(result.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#ADB")) {
                    Log.i(TAG, "Command: ADB");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== ADB Enablement ===\n");

                    try {
                        boolean ok = android.provider.Settings.Global.putInt(
                            getContentResolver(), "adb_enabled", 1);
                        sb.append("Global.putInt(adb_enabled=1): ").append(ok).append("\n");
                    } catch (Exception e) {
                        sb.append("Global.putInt adb: ").append(e).append("\n");
                    }
                    try {
                        boolean ok = android.provider.Settings.Global.putInt(
                            getContentResolver(), "development_settings_enabled", 1);
                        sb.append("Global.putInt(dev_settings=1): ").append(ok).append("\n");
                    } catch (Exception e) {
                        sb.append("Global.putInt dev: ").append(e).append("\n");
                    }

                    String[] cmds = {
                        "setprop persist.sys.usb.config diag,mtp,adb",
                        "start adbd",
                        "setprop service.adb.tcp.port 5555",
                    };
                    for (String c : cmds) {
                        try {
                            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", c});
                            BufferedReader err = new BufferedReader(
                                new InputStreamReader(p.getErrorStream()));
                            StringBuilder errMsg = new StringBuilder();
                            String line;
                            while ((line = err.readLine()) != null) errMsg.append(line);
                            int ret = p.waitFor();
                            sb.append("exec '").append(c).append("' exit=")
                              .append(ret).append("\n");
                        } catch (Exception e) {
                            sb.append("exec '").append(c).append("' failed: ")
                              .append(e).append("\n");
                        }
                    }

                    netOut.write(sb.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#ADB2")) {
                    Log.i(TAG, "Command: ADB2");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== ADB2 Enhanced Enablement ===\n");

                    // a) Settings.Global.putInt
                    sb.append("\n--- a) Settings.Global ---\n");
                    try {
                        boolean ok = android.provider.Settings.Global.putInt(
                            getContentResolver(), "adb_enabled", 1);
                        sb.append("Global.putInt(adb_enabled=1): ").append(ok).append("\n");
                    } catch (Exception e) {
                        sb.append("Global.putInt adb: ").append(truncate(e.toString(), 100)).append("\n");
                    }
                    try {
                        boolean ok = android.provider.Settings.Global.putInt(
                            getContentResolver(), "development_settings_enabled", 1);
                        sb.append("Global.putInt(dev_settings=1): ").append(ok).append("\n");
                    } catch (Exception e) {
                        sb.append("Global.putInt dev: ").append(truncate(e.toString(), 100)).append("\n");
                    }

                    // b) setprop ctl.start adbd
                    sb.append("\n--- b) ctl.start adbd ---\n");
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "setprop ctl.start adbd"});
                        BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                        StringBuilder errMsg = new StringBuilder();
                        String line;
                        while ((line = err.readLine()) != null) errMsg.append(line);
                        int ret = p.waitFor();
                        sb.append("setprop ctl.start adbd exit=").append(ret).append("\n");
                        if (errMsg.length() > 0) sb.append("  stderr: ").append(errMsg).append("\n");
                    } catch (Exception e) {
                        sb.append("  failed: ").append(truncate(e.toString(), 100)).append("\n");
                    }

                    // c) USB config
                    sb.append("\n--- c) USB config ---\n");
                    String[] usbCmds = {
                        "setprop persist.sys.usb.config diag,mtp,adb",
                        "setprop sys.usb.config diag,mtp,adb",
                    };
                    for (String c : usbCmds) {
                        try {
                            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", c});
                            p.waitFor();
                            sb.append("exec '").append(c).append("' exit=").append(p.exitValue()).append("\n");
                        } catch (Exception e) {
                            sb.append("exec '").append(c).append("' failed: ").append(truncate(e.toString(), 100)).append("\n");
                        }
                    }

                    // d) Binder call to adb/adbd
                    sb.append("\n--- d) Binder adb/adbd ---\n");
                    try {
                        Class<?> smClass = Class.forName("android.os.ServiceManager");
                        java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
                        for (String svc : new String[]{"adb", "adbd"}) {
                            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, svc);
                            if (binder != null) {
                                sb.append("Service '").append(svc).append("' found: ").append(binder).append("\n");
                                sb.append("  Ping: ").append(binder.pingBinder()).append("\n");
                                try {
                                    sb.append("  Interface: ").append(binder.getInterfaceDescriptor()).append("\n");
                                } catch (Exception ignored) {}
                            } else {
                                sb.append("Service '").append(svc).append("': null\n");
                            }
                        }
                    } catch (Exception e) {
                        sb.append("  Binder lookup error: ").append(truncate(e.toString(), 100)).append("\n");
                    }

                    // e) Developer settings activity
                    sb.append("\n--- e) Developer settings Activity ---\n");
                    try {
                        Intent devIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        devIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(devIntent);
                        sb.append("Started: ").append(devIntent).append("\n");
                    } catch (Exception e) {
                        sb.append("Failed: ").append(truncate(e.toString(), 100)).append("\n");
                    }

                    // f) Wireless debugging (API 29+)
                    sb.append("\n--- f) Wireless debugging ---\n");
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        try {
                            Intent wdIntent = new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS");
                            wdIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(wdIntent);
                            sb.append("Started wireless debugging settings\n");
                        } catch (Exception e) {
                            sb.append("Failed: ").append(truncate(e.toString(), 100)).append("\n");
                        }
                    } else {
                        sb.append("API ").append(Build.VERSION.SDK_INT).append(" < 29, skipping\n");
                    }

                    // g) Check property state
                    sb.append("\n--- g) Property state ---\n");
                    String[] props = {
                        "persist.adb.tcp.port",
                        "sys.usb.config",
                        "sys.usb.state",
                        "init.svc.adbd",
                        "service.adb.tcp.port",
                        "persist.sys.usb.config",
                        "persist.service.adb.enable",
                        "ro.debuggable",
                        "ro.secure",
                    };
                    for (String prop : props) {
                        try {
                            Process p = Runtime.getRuntime().exec(new String[]{"getprop", prop});
                            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                            String val = r.readLine();
                            p.waitFor();
                            sb.append("  ").append(prop).append(" = ").append(val != null ? val : "(unset)").append("\n");
                        } catch (Exception e) {
                            sb.append("  ").append(prop).append(" = (error: ").append(truncate(e.toString(), 40)).append(")\n");
                        }
                    }

                    // h) SystemProperties reflection
                    sb.append("\n--- h) SystemProperties reflection ---\n");
                    try {
                        Class<?> spClass = Class.forName("android.os.SystemProperties");
                        java.lang.reflect.Method setMethod = spClass.getMethod("set", String.class, String.class);
                        java.lang.reflect.Method getMethod = spClass.getMethod("get", String.class, String.class);

                        String adbPort = (String) getMethod.invoke(null, "service.adb.tcp.port");
                        sb.append("Current service.adb.tcp.port = ").append(adbPort != null ? adbPort : "null").append("\n");

                        setMethod.invoke(null, "service.adb.tcp.port", "5555");
                        sb.append("Set service.adb.tcp.port = 5555\n");

                        String verify = (String) getMethod.invoke(null, "service.adb.tcp.port");
                        sb.append("Verified: service.adb.tcp.port = ").append(verify).append("\n");

                        setMethod.invoke(null, "persist.service.adb.enable", "1");
                        sb.append("Set persist.service.adb.enable = 1\n");

                    } catch (ClassNotFoundException e) {
                        sb.append("SystemProperties class not found\n");
                    } catch (NoSuchMethodException e) {
                        sb.append("set/get method not found\n");
                    } catch (Exception e) {
                        sb.append("Error: ").append(truncate(e.toString(), 100)).append("\n");
                    }

                    sb.append("\n--- Restart adbd with new config ---\n");
                    String[] restartCmds = {
                        "stop adbd",
                        "sleep 0.5",
                        "start adbd",
                    };
                    for (String c : restartCmds) {
                        try {
                            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", c});
                            p.waitFor();
                            sb.append("  ").append(c).append(" exit=").append(p.exitValue()).append("\n");
                        } catch (Exception e) {
                            sb.append("  ").append(c).append(" failed: ").append(truncate(e.toString(), 60)).append("\n");
                        }
                    }

                    netOut.write(sb.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#GETDATA")) {
                    Log.i(TAG, "Command: GETDATA");
                    OutputStream netOut = client.getOutputStream();
                    try {
                        File f = new File("/sdcard/Download/car_scan.txt");
                        if (f.exists()) {
                            BufferedReader fr = new BufferedReader(new java.io.FileReader(f));
                            String line;
                            while ((line = fr.readLine()) != null) {
                                netOut.write((line + "\n").getBytes());
                            }
                            fr.close();
                        } else {
                            netOut.write("(no scan data yet, run #SCAN first)\n".getBytes());
                        }
                    } catch (Exception e) {
                        netOut.write(("(read error: " + e + ")\n").getBytes());
                    }
                    netOut.flush();

                } else if (cmd.equals("#SCANV4")) {
                    Log.i(TAG, "Command: SCANV4");
                    OutputStream netOut = client.getOutputStream();
                    CarScanner cs = new CarScanner(getContentResolver(), ShellService.this);
                    cs.setCallback(result -> {
                        try {
                            netOut.write(result.getBytes());
                            netOut.flush();
                        } catch (IOException e) {
                            Log.e(TAG, "SCANV4 write error", e);
                        }
                    });
                    cs.scan();

                } else if (cmd.equals("#SETTINGS")) {
                    Log.i(TAG, "Command: SETTINGS");
                    OutputStream netOut = client.getOutputStream();
                    CarScanner cs = new CarScanner(getContentResolver(), ShellService.this);
                    cs.setCallback(result -> {
                        try {
                            netOut.write(result.getBytes());
                            netOut.flush();
                        } catch (IOException e) {
                            Log.e(TAG, "SETTINGS write error", e);
                        }
                    });
                    cs.dumpSettings();

                } else if (cmd.equals("#QUERY") || cmd.startsWith("#QUERY ")) {
                    // 使用 ContentResolver 查询 ContentProvider
                    String rest = firstLine.trim().substring(6).trim();
                    Log.i(TAG, "Command: QUERY " + rest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String uriStr, selection, selArgs;

                    // Parse: <uri> [<selection>] [<arg1>,<arg2>,...]
                    int sp1 = rest.indexOf(' ');
                    if (sp1 < 0) {
                        uriStr = rest;
                        selection = null;
                        selArgs = null;
                    } else {
                        uriStr = rest.substring(0, sp1);
                        String rest2 = rest.substring(sp1 + 1).trim();
                        int sp2 = rest2.indexOf(' ');
                        if (sp2 < 0) {
                            selection = rest2.isEmpty() ? null : rest2;
                            selArgs = null;
                        } else {
                            selection = rest2.substring(0, sp2).trim();
                            String argsPart = rest2.substring(sp2 + 1).trim();
                            selArgs = argsPart.isEmpty() ? null : argsPart;
                        }
                    }

                    if (uriStr.isEmpty()) {
                        res.append("Usage: #QUERY <content_uri> [where] [arg1,arg2,...]\n");
                        res.append("  e.g. #QUERY content://com.patac.hmi.css/\n");
                        res.append("  e.g. #QUERY content://com.patac.hmi.css/switch name=? enabled\n");
                    } else {
                        try {
                            Uri uri = Uri.parse(uriStr);
                            String[] selArgArr = (selArgs != null && !selArgs.isEmpty())
                                ? selArgs.split(",")
                                : null;
                            android.database.Cursor cursor = getContentResolver().query(
                                uri, null, selection, selArgArr, null);
                            if (cursor == null) {
                                res.append("ERROR: query returned null\n");
                            } else {
                                int colCount = cursor.getColumnCount();
                                String[] colNames = cursor.getColumnNames();
                                res.append("Columns: ").append(colCount).append("\n");
                                String header = "";
                                for (int i = 0; i < colCount; i++) {
                                    if (i > 0) header += " | ";
                                    header += colNames[i];
                                }
                                res.append(header).append("\n");
                                res.append("---").append("\n");
                                int rowCount = 0;
                                while (cursor.moveToNext()) {
                                    String row = "";
                                    for (int i = 0; i < colCount; i++) {
                                        if (i > 0) row += " | ";
                                        try {
                                            int type = cursor.getType(i);
                                            switch (type) {
                                                case android.database.Cursor.FIELD_TYPE_NULL:
                                                    row += "NULL"; break;
                                                case android.database.Cursor.FIELD_TYPE_INTEGER:
                                                    row += cursor.getLong(i); break;
                                                case android.database.Cursor.FIELD_TYPE_FLOAT:
                                                    row += cursor.getDouble(i); break;
                                                case android.database.Cursor.FIELD_TYPE_STRING:
                                                    row += cursor.getString(i); break;
                                                case android.database.Cursor.FIELD_TYPE_BLOB:
                                                    row += "[BLOB " + cursor.getBlob(i).length + "b]"; break;
                                                default:
                                                    row += "[type=" + type + "]";
                                            }
                                        } catch (Exception e) {
                                            row += "[err: " + truncate(e.toString(), 30) + "]";
                                        }
                                    }
                                    res.append(row).append("\n");
                                    rowCount++;
                                    if (rowCount > 200) {
                                        res.append("... (truncated at 200 rows)\n");
                                        break;
                                    }
                                }
                                res.append("---\nRows: ").append(rowCount).append("\n");
                                cursor.close();
                            }
                        } catch (SecurityException e) {
                            res.append("SECURITY: ").append(truncate(e.toString(), 200)).append("\n");
                        } catch (Exception e) {
                            res.append("ERROR: ").append(truncate(e.toString(), 200)).append("\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#VEHICLE")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder vb = new StringBuilder();
                    vb.append("=== VEHICLE STATE ===\n");
                    String ignStr;
                    switch (lastIgnitionState) {
                        case 0: ignStr = "OFF"; break;
                        case 1: ignStr = "ON"; break;
                        default: ignStr = "Unknown";
                    }
                    vb.append("  Ignition: ").append(ignStr).append(" (").append(lastIgnitionState).append(")\n");
                    String dmStr;
                    switch (lastDriveMode) {
                        case 0: dmStr = "P (Park)"; break;
                        case 1: dmStr = "R (Reverse)"; break;
                        case 2: dmStr = "N (Neutral)"; break;
                        case 3: dmStr = "D (Drive)"; break;
                        default: dmStr = "Unknown";
                    }
                    vb.append("  Drive Mode: ").append(dmStr).append(" (").append(lastDriveMode).append(")\n");
                    if (lastVehicleUpdate > 0) {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                        vb.append("  Last Update: ").append(sdf.format(new java.util.Date(lastVehicleUpdate))).append("\n");
                    } else {
                        vb.append("  Last Update: never (waiting for broadcast)\n");
                    }
                    netOut.write(vb.toString().getBytes());
                    netOut.flush();
                } else if (cmd.startsWith("#ACT ")) {
                    // #ACT <pkg> <cls> [--es key val] [--eu key uri] [--ef key filepath]
                    String rest = firstLine.trim().substring(5).trim();
                    Log.i(TAG, "Command: ACT " + rest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    
                    String[] parts = rest.split(" ");
                    if (parts.length < 2) {
                        res.append("Usage: #ACT <package> <class> [--action act] [--es k v] [--eu uri] [--ef file]\n");
                    } else {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName(parts[0], parts[1]);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            
                            for (int i = 2; i < parts.length; i++) {
                                if (parts[i].equals("--action") && i + 1 < parts.length) {
                                    intent.setAction(parts[i+1]);
                                    i++;
                                } else if (parts[i].equals("--es") && i + 2 < parts.length) {
                                    intent.putExtra(parts[i+1], parts[i+2]);
                                    i += 2;
                                } else if (parts[i].equals("--eu") && i + 2 < parts.length) {
                                    intent.setData(android.net.Uri.parse(parts[i+2]));
                                    i += 2;
                                } else if (parts[i].equals("--ef") && i + 2 < parts.length) {
                                    intent.setDataAndType(android.net.Uri.fromFile(new java.io.File(parts[i+2])), 
                                        "application/vnd.android.package-archive");
                                    i += 2;
                                }
                            }
                            
                            startActivity(intent);
                            res.append("Started: ").append(intent).append("\n");
                        } catch (Exception e) {
                            res.append("Failed: ").append(truncate(e.toString(), 200)).append("\n");
                            java.io.StringWriter sw = new java.io.StringWriter();
                            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                            e.printStackTrace(pw);
                            res.append("  Trace: ").append(sw.toString().substring(0, Math.min(500, sw.toString().length()))).append("\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#ENUM")) {
                    Log.i(TAG, "Command: ENUM");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== PACKAGE ENUMERATION ===\n");
                    String[] cmds = {
                        "pm list packages -3 --user 10",
                        "pm list packages --user 10 | grep -iE 'obd|onstar|patac|gm\\.server|vehicle|diagnos|eng|develop|shell|car\\.input|service\\.bus|v2x|pki|datatrack|iotservice|asd|kanzi|cls'",
                    };
                    for (String c : cmds) {
                        try {
                            res.append("--- ").append(c).append(" ---\n");
                            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", c});
                            BufferedReader r = new BufferedReader(
                                new InputStreamReader(p.getInputStream()));
                            String line;
                            while ((line = r.readLine()) != null) {
                                res.append(line).append("\n");
                            }
                            p.waitFor();
                        } catch (Exception e) {
                            res.append("(cmd error: ").append(e).append(")\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#LAUNCH ")) {
                    // 启动指定包名的 App
                    String targetPkg = firstLine.trim().substring(8).trim();
                    Log.i(TAG, "Command: LAUNCH " + targetPkg);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== LAUNCH ").append(targetPkg).append(" ===\n");
                    try {
                        PackageManager pm = getPackageManager();
                        Intent intent = pm.getLaunchIntentForPackage(targetPkg);
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            res.append("  Intent: ").append(intent).append("\n");
                            res.append("  Component: ").append(intent.getComponent()).append("\n");
                            res.append("  Result: STARTED\n");
                        } else {
                            res.append("  No launcher intent found\n");
                            // Try to find any exported activity
                            Intent query = new Intent(Intent.ACTION_MAIN);
                            query.setPackage(targetPkg);
                            List<ResolveInfo> acts = pm.queryIntentActivities(query, 0);
                            if (acts != null && !acts.isEmpty()) {
                                for (ResolveInfo ri : acts) {
                                    res.append("  Activity: ").append(ri.activityInfo.name)
                                       .append(" exported=").append(ri.activityInfo.exported).append("\n");
                                    if (ri.activityInfo.exported) {
                                        try {
                                            Intent ai = new Intent();
                                            ai.setClassName(targetPkg, ri.activityInfo.name);
                                            ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                            startActivity(ai);
                                            res.append("    -> STARTED!\n");
                                        } catch (Exception e2) {
                                            res.append("    -> ").append(truncate(e2.toString(), 60)).append("\n");
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        res.append("  Error: ").append(e).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#NET")) {
                    Log.i(TAG, "Command: NET");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== NETWORK INFO ===\n");
                    try {
                        java.util.Enumeration<java.net.NetworkInterface> ifaces =
                            java.net.NetworkInterface.getNetworkInterfaces();
                        while (ifaces.hasMoreElements()) {
                            java.net.NetworkInterface iface = ifaces.nextElement();
                            res.append("  ").append(iface.getName()).append(":\n");
                            res.append("    MTU=").append(iface.getMTU()).append("\n");
                            res.append("    Loopback=").append(iface.isLoopback()).append("\n");
                            res.append("    Up=").append(iface.isUp()).append("\n");
                            java.util.Enumeration<java.net.InetAddress> addrs =
                                iface.getInetAddresses();
                            while (addrs.hasMoreElements()) {
                                java.net.InetAddress addr = addrs.nextElement();
                                res.append("    IP: ").append(addr.getHostAddress()).append("\n");
                            }
                        }
                    } catch (Exception e) {
                        res.append("  Error: ").append(e).append("\n");
                    }
                    // Also try to resolve a hostname to test connectivity
                    res.append("\n=== DNS TEST ===\n");
                    try {
                        java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName("www.baidu.com");
                        for (java.net.InetAddress a : addrs) {
                            res.append("  ").append(a.getHostAddress()).append("\n");
                        }
                    } catch (Exception e) {
                        res.append("  DNS failed: ").append(e).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#DUMPPKG ")) {
                    String pkg = firstLine.trim().substring(9).trim();
                    Log.i(TAG, "Command: DUMPPKG " + pkg);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== PACKAGE: ").append(pkg).append(" ===\n");
                    try {
                        PackageInfo pi = getPackageManager().getPackageInfo(pkg,
                            PackageManager.GET_ACTIVITIES |
                            PackageManager.GET_SERVICES |
                            PackageManager.GET_PROVIDERS |
                            PackageManager.GET_RECEIVERS |
                            PackageManager.GET_META_DATA);
                        res.append("Version: ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
                        res.append("\n--- ACTIVITIES ---\n");
                        if (pi.activities != null) {
                            for (ActivityInfo ai : pi.activities) {
                                res.append("  ").append(ai.name)
                                   .append(" exported=").append(ai.exported)
                                   .append(" enabled=").append(ai.enabled).append("\n");
                            }
                        }
                        res.append("\n--- SERVICES ---\n");
                        if (pi.services != null) {
                            for (ServiceInfo si : pi.services) {
                                res.append("  ").append(si.name)
                                   .append(" exported=").append(si.exported)
                                   .append(" permission=").append(si.permission != null ? si.permission : "(none)")
                                   .append("\n");
                            }
                        }
                        res.append("\n--- PROVIDERS ---\n");
                        if (pi.providers != null) {
                            for (ProviderInfo pri : pi.providers) {
                                res.append("  ").append(pri.name)
                                   .append(" authority=").append(pri.authority)
                                   .append(" exported=").append(pri.exported)
                                   .append(" readPerm=").append(pri.readPermission != null ? pri.readPermission : "(none)")
                                   .append("\n");
                            }
                        }
                        res.append("\n--- RECEIVERS ---\n");
                        if (pi.receivers != null) {
                            for (ActivityInfo ri : pi.receivers) {
                                res.append("  ").append(ri.name)
                                   .append(" exported=").append(ri.exported).append("\n");
                            }
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        res.append("Package not found\n");
                    } catch (Exception e) {
                        res.append("Error: ").append(truncate(e.toString(), 200)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#CALL ")) {
                    String rest = firstLine.trim().substring(6).trim();
                    Log.i(TAG, "Command: CALL " + rest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String[] parts = rest.split(" ", 3);
                    if (parts.length < 2) {
                        res.append("Usage: #CALL <uri> <method> [arg]\n");
                    } else {
                        try {
                            Uri uri = Uri.parse(parts[0]);
                            String method = parts[1];
                            String arg = parts.length > 2 ? parts[2] : null;
                            Bundle bundle = null;
                            android.os.Bundle result = getContentResolver().call(uri, method, arg, bundle);
                            if (result == null) {
                                res.append("Result: null\n");
                            } else {
                                res.append("Result: ").append(result.toString()).append("\n");
                                for (String key : result.keySet()) {
                                    res.append("  ").append(key).append(" = ").append(result.get(key)).append("\n");
                                }
                            }
                        } catch (SecurityException e) {
                            res.append("SECURITY: ").append(truncate(e.toString(), 200)).append("\n");
                        } catch (Exception e) {
                            res.append("ERROR: ").append(truncate(e.toString(), 200)).append("\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#INSTALLB64")) {
                    Log.i(TAG, "Command: INSTALLB64");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== INSTALLB64 ===\n");
                    
                    try {
                        // Read base64 data line by line until --END--
                        StringBuilder b64data = new StringBuilder();
                        String line;
                        while ((line = netReader.readLine()) != null) {
                            if (line.trim().equals("--END--")) break;
                            b64data.append(line.replace(" ", "").replace("\n", "").replace("\r", ""));
                        }
                        
                        if (b64data.length() == 0) {
                            res.append("No base64 data received\n");
                        } else {
                            res.append("Received ").append(b64data.length()).append(" b64 chars\n");
                            
                            // Base64 decode
                            byte[] apkBytes = android.util.Base64.decode(
                                b64data.toString(), android.util.Base64.DEFAULT);
                            res.append("Decoded: ").append(apkBytes.length).append(" bytes\n");
                            
                            // Write to internal storage
                            File dstDir = getFilesDir();
                            if (!dstDir.exists()) dstDir.mkdirs();
                            File dstFile = new File(dstDir, "update.apk");
                            FileOutputStream fos = new FileOutputStream(dstFile);
                            fos.write(apkBytes);
                            fos.close();
                            res.append("Written to: ").append(dstFile.getAbsolutePath()).append("\n");
                            
                            // Install via PackageInstaller
                            if (android.os.Build.VERSION.SDK_INT >= 21) {
                                android.content.pm.PackageInstaller installer = 
                                    getPackageManager().getPackageInstaller();
                                android.content.pm.PackageInstaller.SessionParams params = 
                                    new android.content.pm.PackageInstaller.SessionParams(
                                        android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                                params.setAppPackageName("com.vox.shellbridge");
                                
                                int sessionId = installer.createSession(params);
                                android.content.pm.PackageInstaller.Session session = installer.openSession(sessionId);
                                OutputStream out = session.openWrite("update", 0, apkBytes.length);
                                out.write(apkBytes);
                                session.fsync(out);
                                out.close();
                                
                                Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                                android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                                    ShellService.this, 0, intent,
                                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                                session.commit(pendingIntent.getIntentSender());
                                session.close();
                                
                                res.append("Install session committed (sessionId=" + sessionId + ")\n");
                                res.append("Check notification / screen for install prompt\n");
                            } else {
                                res.append("API too old for PackageInstaller\n");
                            }
                        }
                    } catch (SecurityException e) {
                        res.append("SECURITY: ").append(truncate(e.toString(), 200)).append("\n");
                    } catch (Exception e) {
                        res.append("Error: ").append(truncate(e.toString(), 300)).append("\n");
                        java.io.StringWriter sw = new java.io.StringWriter();
                        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                        e.printStackTrace(pw);
                        String trace = sw.toString();
                        res.append("  Trace: ").append(trace.substring(0, Math.min(500, trace.length()))).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#UPDATE ")) {
                    String rest = firstLine.trim().substring(8).trim();
                    Log.i(TAG, "Command: UPDATE " + rest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    
                    String srcPath = rest.isEmpty() ? "/sdcard/Download/ShellBridge_v19.bin" : rest;
                    res.append("=== UPDATE from ").append(srcPath).append(" ===\n");
                    
                    try {
                        // Read source file
                        File srcFile = new File(srcPath);
                        if (!srcFile.exists()) {
                            res.append("Source not found: ").append(srcPath).append("\n");
                        } else {
                            long len = srcFile.length();
                            res.append("Source size: ").append(len).append(" bytes\n");
                            
                            // Write to internal storage (app-private)
                            File dstDir = getFilesDir();
                            if (!dstDir.exists()) dstDir.mkdirs();
                            File dstFile = new File(dstDir, "update.apk");
                            
                            java.io.FileInputStream fis = new java.io.FileInputStream(srcFile);
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(dstFile);
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = fis.read(buf)) != -1) {
                                fos.write(buf, 0, n);
                            }
                            fis.close();
                            fos.close();
                            
                            res.append("Copied to: ").append(dstFile.getAbsolutePath()).append("\n");
                            res.append("Dest size: ").append(dstFile.length()).append(" bytes\n");
                            
                            // Try to install via PackageInstaller
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                android.content.pm.PackageInstaller installer = 
                                    getPackageManager().getPackageInstaller();
                                android.content.pm.PackageInstaller.SessionParams params = 
                                    new android.content.pm.PackageInstaller.SessionParams(
                                        android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                                params.setAppPackageName("com.vox.shellbridge");
                                
                                int sessionId = installer.createSession(params);
                                android.content.pm.PackageInstaller.Session session = installer.openSession(sessionId);
                                
                                java.io.InputStream in = new java.io.FileInputStream(dstFile);
                                java.io.OutputStream out = session.openWrite("ShellBridge_v19", 0, dstFile.length());
                                byte[] buf2 = new byte[8192];
                                int n2;
                                while ((n2 = in.read(buf2)) != -1) {
                                    out.write(buf2, 0, n2);
                                }
                                session.fsync(out);
                                out.close();
                                in.close();
                                
                                // Commit
                                android.content.Intent intent = new android.content.Intent(
                                    android.content.Intent.ACTION_INSTALL_PACKAGE);
                                android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                                    ShellService.this, 0, intent,
                                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                                session.commit(pendingIntent.getIntentSender());
                                session.close();
                                
                                res.append("Install session committed (sessionId=" + sessionId + ")\n");
                                res.append("Check notification for confirmation\n");
                            } else {
                                res.append("API too old for PackageInstaller\n");
                            }
                        }
                    } catch (SecurityException e) {
                        res.append("SECURITY: ").append(truncate(e.toString(), 200)).append("\n");
                    } catch (Exception e) {
                        res.append("Error: ").append(truncate(e.toString(), 300)).append("\n");
                        java.io.StringWriter sw = new java.io.StringWriter();
                        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                        e.printStackTrace(pw);
                        String trace = sw.toString();
                        res.append("  Trace: ").append(trace.substring(0, Math.min(500, trace.length()))).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#SERVE")) {
                    Log.i(TAG, "Command: SERVE");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== HTTP FILE SERVER ===\n");
                    final int httpPort = 8899;
                    try {
                        class HttpFileServer implements Runnable {
                            @Override
                            public void run() {
                                try {
                                    java.net.ServerSocket ss = new java.net.ServerSocket(httpPort);
                                    byte[] apkBytes = null;
                                    File apkFile = new File(getFilesDir(), "update.apk");
                                    if (apkFile.exists()) {
                                        java.io.FileInputStream fis = new java.io.FileInputStream(apkFile);
                                        apkBytes = new byte[(int)apkFile.length()];
                                        fis.read(apkBytes);
                                        fis.close();
                                    }
                                    while (true) {
                                        try {
                                            java.net.Socket s = ss.accept();
                                            java.io.OutputStream os = s.getOutputStream();
                                            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()));
                                            String req = r.readLine();
                                            while (r.readLine() != null && r.readLine().length() > 0) {}
                                            if (req != null && req.contains("/ShellBridge.apk")) {
                                                if (apkBytes != null) {
                                                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: " + apkBytes.length + "\r\n\r\n").getBytes());
                                                    os.write(apkBytes);
                                                } else {
                                                    os.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                                                }
                                            } else {
                                                String html = "<html><body><h2>ShellBridge APK</h2><a href='/ShellBridge.apk'>Download ShellBridge v1.10</a></body></html>";
                                                os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + html.length() + "\r\n\r\n" + html).getBytes());
                                            }
                                            os.close();
                                        } catch (Exception e) {}
                                    }
                                } catch (Exception e) {}
                            }
                        }
                        new Thread(new HttpFileServer()).start();
                        res.append("HTTP server started on port " + httpPort + "\n");
                        res.append("Download: http://<TS_IP>:" + httpPort + "/ShellBridge.apk\n");
                    } catch (Exception e) {
                        res.append("Error: ").append(truncate(e.toString(), 200)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#ACTION ")) {
                    String action = firstLine.trim().substring(8).trim();
                    Log.i(TAG, "Command: ACTION " + action);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== SEND INTENT: ").append(action).append(" ===\n");
                    try {
                        Intent intent = new Intent(action);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intent);
                        res.append("  Intent sent: ").append(intent).append("\n");
                        res.append("  Result: STARTED\n");
                    } catch (Exception e) {
                        res.append("  Error: ").append(truncate(e.toString(), 100)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#BINDSVC ")) {
                    // #BINDSVC <pkg> <cls> [code1] [arg1] [-- code2 arg2 code3 arg3...]
                    // Two-step: code1+arg calls getPatacService on main binder,
                    // then code2+arg+code3+arg... calls on the returned sub-binder
                    String rest = firstLine.trim().substring(9).trim();
                    Log.i(TAG, "Command: BINDSVC " + rest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== BINDSVC: ").append(rest).append(" ===\n");

                    // Split into two parts at " -- "
                    String mainPart, chainPart;
                    int chainIdx = rest.indexOf(" -- ");
                    if (chainIdx > 0) {
                        mainPart = rest.substring(0, chainIdx).trim();
                        chainPart = rest.substring(chainIdx + 4).trim();
                    } else {
                        mainPart = rest;
                        chainPart = null;
                    }

                    String[] parts = mainPart.split(" ", 5);
                    if (parts.length < 2) {
                        res.append("Usage:\n");
                        res.append("  Step1: #BINDSVC <pkg> <cls> [code(hex)] [strArg1] [strArg2]\n");
                        res.append("  Step2: ... -- <code2> <strArg> <code3> <strArg>\n");
                        res.append("  e.g.: #BINDSVC com.patac.service .PatacService 1 PatacEngineeringModeService -- 1 2\n");
                    } else {
                        String pkg = parts[0];
                        String cls = parts[1];
                        if (!cls.startsWith(".") && !cls.contains(".")) {
                            cls = "." + cls;
                        }
                        if (cls.startsWith(".")) {
                            cls = pkg + cls;
                        }

                        final CountDownLatch latch = new CountDownLatch(1);
                        final IBinder[] boundBinder = new IBinder[1];
                        final String[] errorMsg = new String[1];

                        try {
                            Intent intent = new Intent();
                            intent.setClassName(pkg, cls);

                            boolean bound = bindService(intent, new ServiceConnection() {
                                @Override
                                public void onServiceConnected(ComponentName name, IBinder service) {
                                    boundBinder[0] = service;
                                    latch.countDown();
                                }
                                @Override
                                public void onServiceDisconnected(ComponentName name) {
                                    errorMsg[0] = "disconnected";
                                    latch.countDown();
                                }
                            }, ShellService.this.BIND_AUTO_CREATE);

                            if (!bound) {
                                res.append("bindService returned false\n");
                            } else {
                                boolean timedOut = !latch.await(5000, TimeUnit.MILLISECONDS);
                                if (timedOut) {
                                    res.append("Timed out (5s)\n");
                                } else if (boundBinder[0] == null) {
                                    res.append("Got null binder\n");
                                } else {
                                    IBinder svc = boundBinder[0];
                                    res.append("Connected: ").append(svc).append("\n");
                                    res.append("Iface: ").append(svc.getInterfaceDescriptor()).append("\n");
                                    res.append("Ping: ").append(svc.pingBinder()).append("\n");

                                    // Helper: transact once and return the reply binder (if any)
                                    java.util.function.Function<String[], IBinder> doTransact = new java.util.function.Function<String[], IBinder>() {
                                        @Override
                                        public IBinder apply(String[] args) {
                                            if (args.length < 1) return svc;
                                            int tCode = Integer.parseInt(args[0]);
                                            try {
                                                android.os.Parcel d = android.os.Parcel.obtain();
                                                android.os.Parcel r = android.os.Parcel.obtain();
                                                try {
                                                    d.writeInterfaceToken(svc.getInterfaceDescriptor());
                                                    for (int i = 1; i < args.length; i++) {
                                                        String a = args[i];
                                                        if (a.startsWith("i:")) {
                                                            d.writeInt(Integer.parseInt(a.substring(2)));
                                                        } else if (a.startsWith("l:")) {
                                                            d.writeLong(Long.parseLong(a.substring(2)));
                                                        } else if (a.startsWith("b:")) {
                                                            d.writeByte(Byte.parseByte(a.substring(2)));
                                                        } else if (a.startsWith("f:")) {
                                                            d.writeFloat(Float.parseFloat(a.substring(2)));
                                                        } else if (a.startsWith("d:")) {
                                                            d.writeDouble(Double.parseDouble(a.substring(2)));
                                                        } else if (a.startsWith("s:")) {
                                                            d.writeString(a.substring(2));
                                                        } else {
                                                            d.writeString(a);
                                                        }
                                                    }
                                                    boolean ok = svc.transact(tCode, d, r, 0);
                                                    res.append("tr("+tCode+"): "+ok+"\n");
                                                    if (ok && r.dataAvail() > 0) {
                                                        // AIDL reply: [int exceptionCode, ...returnValue]
                                                        int excCode = 0;
                                                        int startPos = r.dataPosition();
                                                        try { excCode = r.readInt(); } catch (Exception e) {}
                                                        if (excCode != 0 && excCode != -1) {
                                                            res.append("  -> ExcCode: "+excCode+"\n");
                                                        }
                                                        // Try reading as Binder
                                                        try {
                                                            IBinder sub = r.readStrongBinder();
                                                            if (sub != null) {
                                                                res.append("  -> Binder: "+sub+"\n");
                                                                try { res.append("  -> Iface: "+sub.getInterfaceDescriptor()+"\n"); } catch (Exception e) {}
                                                                return sub;
                                                            }
                                                        } catch (Exception e) {}
                                                        // Try string
                                                        try {
                                                            String s = r.readString();
                                                            if (s != null) res.append("  -> String: "+s+"\n");
                                                        } catch (Exception e) {}
                                                        // Try int
                                                        try {
                                                            r.setDataPosition(startPos);
                                                            r.readInt(); // exc
                                                            int iv = r.readInt();
                                                            res.append("  -> Int: "+iv+"\n");
                                                        } catch (Exception e) {}
                                                        int remaining = r.dataAvail();
                                                        if (remaining > 0) {
                                                            byte[] buf = new byte[Math.min(remaining, 64)];
                                                            r.readByteArray(buf);
                                                            res.append("  -> Raw("+remaining+"): ");
                                                            for (int bi = 0; bi < Math.min(remaining, 32); bi++)
                                                                res.append(String.format("%02x", buf[bi]&0xff));
                                                            res.append("\n");
                                                        }
                                                    }
                                                    r.recycle(); d.recycle();
                                                } catch (Exception e) {
                                                    res.append("tr("+tCode+") err: "+truncate(e.toString(),80)+"\n");
                                                }
                                            } catch (Exception e) {}
                                            return svc; // return current binder if sub-binder extraction failed
                                        }
                                    };

                                    // Step 1: call on main service
                                    String[] mainArgs = new String[parts.length - 2];
                                    for (int i = 2; i < parts.length; i++) mainArgs[i-2] = parts[i];
                                    IBinder currentBinder = doTransact.apply(mainArgs);

                                    // Step 2: if chain exists, call step 2 on the returned binder
                                    if (chainPart != null && !chainPart.isEmpty()) {
                                        res.append("--- Step 2 (on sub-binder) ---\n");
                                        String[] chainArgs = chainPart.split(" ");
                                        try {
                                            // chainArgs: [code0, arg0?, code1, arg1?, ...]
                                            int ci = 0;
                                            while (ci < chainArgs.length) {
                                                int c = Integer.parseInt(chainArgs[ci]);
                                                ci++;
                                                android.os.Parcel d2 = android.os.Parcel.obtain();
                                                android.os.Parcel r2 = android.os.Parcel.obtain();
                                                try {
                                                    d2.writeInterfaceToken(currentBinder.getInterfaceDescriptor());
                                                    // Write remaining args until next numeric code
                                                    int startCi = ci;
                                                    while (ci < chainArgs.length) {
                                                        try {
                                                            Integer.parseInt(chainArgs[ci]);
                                                            break;
                                                        } catch (NumberFormatException e) {
                                                            String arg = chainArgs[ci];
                                                            if (arg.startsWith("i:")) {
                                                                d2.writeInt(Integer.parseInt(arg.substring(2)));
                                                            } else if (arg.startsWith("s:")) {
                                                                d2.writeString(arg.substring(2));
                                                            } else if (arg.startsWith("b:")) {
                                                                d2.writeByte(Byte.parseByte(arg.substring(2)));
                                                            } else if (arg.equalsIgnoreCase("true")) {
                                                                d2.writeInt(1);
                                                            } else if (arg.equalsIgnoreCase("false")) {
                                                                d2.writeInt(0);
                                                            } else {
                                                                try {
                                                                    d2.writeInt(Integer.parseInt(arg));
                                                                } catch (NumberFormatException e2) {
                                                                    d2.writeString(arg);
                                                                }
                                                            }
                                                            ci++;
                                                        }
                                                    }
                                                    res.append("Call sub-binder: tr("+c+") "+"\n");
                                                    boolean ok = currentBinder.transact(c, d2, r2, 0);
                                                    res.append("  ok="+ok+"\n");
                                                    if (ok && r2.dataAvail() > 0) {
                                                        int excC = r2.readInt();
                                                        if (excC != 0 && excC != -1) res.append("  exc="+excC+"\n");
                                                        try {
                                                            String s = r2.readString();
                                                            if (s != null) res.append("  -> String: "+s+"\n");
                                                        } catch (Exception e2) {}
                                                        try {
                                                            r2.setDataPosition(4);
                                                            int iv = r2.readInt();
                                                            res.append("  -> Int: "+iv+"\n");
                                                        } catch (Exception e2) {}
                                                    }
                                                    r2.recycle(); d2.recycle();
                                                } catch (Exception e) {
                                                    res.append("  err: "+truncate(e.toString(),60)+"\n");
                                                    d2.recycle(); r2.recycle();
                                                }
                                            }
                                        } catch (Exception e) {
                                            res.append("  chain parse err: "+truncate(e.toString(),80)+"\n");
                                        }
                                    }
                                }
                                try { unbindService(new ServiceConnection() {
                                    public void onServiceConnected(ComponentName n, IBinder s) {}
                                    public void onServiceDisconnected(ComponentName n) {}
                                }); } catch (Exception ignored) {}
                            }
                        } catch (SecurityException e) {
                            res.append("SECURITY: "+truncate(e.toString(),200)+"\n");
                        } catch (Exception e) {
                            res.append("Error: "+truncate(e.toString(),300)+"\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#RAW ")) {
                    String rest = firstLine.trim().substring(5).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== RAW: ").append(rest).append(" ===\n");
                    String[] rp = rest.split(" ");
                    ServiceConnection rc = null;
                    try {
                        if (rp.length < 2) {
                            res.append("  usage: #RAW <pkg> <cls> [code] [s:str|i:int ...]\n");
                        } else {
                            final CountDownLatch rl = new CountDownLatch(1);
                            final IBinder[] rb = new IBinder[1];
                            rc = new ServiceConnection() {
                                public void onServiceConnected(ComponentName n, IBinder b) { rb[0] = b; rl.countDown(); }
                                public void onServiceDisconnected(ComponentName n) {}
                            };
                            android.content.Intent ri = new android.content.Intent();
                            String cls = rp[1];
                            if (!cls.startsWith(".") && !cls.contains(".")) cls = "." + cls;
                            if (cls.startsWith(".")) cls = rp[0] + cls;
                            ri.setClassName(rp[0], cls);
                            bindService(ri, rc, BIND_AUTO_CREATE);
                            rl.await(6, java.util.concurrent.TimeUnit.SECONDS);
                            IBinder b = rb[0];
                            res.append("  binder=").append(b).append("\n");
                            if (b != null) {
                                String iface = null;
                                try { iface = b.getInterfaceDescriptor(); } catch (Exception ignored) {}
                                res.append("  iface=").append(iface).append("\n");
                                int code = 1;
                                try { if (rp.length > 2) code = Integer.parseInt(rp[2]); } catch (Exception ignored) {}
                                android.os.Parcel d = android.os.Parcel.obtain();
                                android.os.Parcel r = android.os.Parcel.obtain();
                                try {
                                    if (iface != null) d.writeInterfaceToken(iface);
                                    for (int i = 3; i < rp.length; i++) {
                                        String a = rp[i];
                                        if (a.startsWith("s:")) d.writeString(a.substring(2));
                                        else if (a.startsWith("i:")) d.writeInt(Integer.parseInt(a.substring(2)));
                                        else if (a.startsWith("l:")) d.writeLong(Long.parseLong(a.substring(2)));
                                    }
                                    boolean ok = b.transact(code, d, r, 0);
                                    res.append("  ok=").append(ok).append(" size=").append(r.dataSize()).append("\n");
                                    try { byte[] raw = r.marshall(); int lim = Math.min(raw.length, 300); StringBuilder hex = new StringBuilder(); for (int i = 0; i < lim; i++) hex.append(String.format("%02x", raw[i] & 0xFF)); res.append("  hex=").append(hex).append("\n"); } catch (Exception e1) { res.append("  hex-err=").append(e1).append("\n"); }
                                    try { r.setDataPosition(0); r.readException(); res.append("  no-exception\n"); } catch (Exception e2) { res.append("  exc=").append(truncate(String.valueOf(e2.getMessage()), 120)).append("\n"); }
                                } catch (Exception e) {
                                    res.append("  call err: ").append(truncate(e.toString(), 140)).append("\n");
                                } finally { d.recycle(); r.recycle(); }
                            }
                        }
                    } catch (Exception e) {
                        res.append("  Error: ").append(truncate(e.toString(), 160)).append("\n");
                    } finally {
                        try { if (rc != null) unbindService(rc); } catch (Exception ignored) {}
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#CLS ")) {
                    String cn = firstLine.trim().substring(4).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== CLASS: ").append(cn).append(" ===\n");
                    try {
                        Class<?> cc = Class.forName(cn);
                        res.append("  loader=").append(cc.getClassLoader()).append("\n");
                        for (java.lang.reflect.Constructor<?> ct : cc.getDeclaredConstructors()) {
                            res.append("  CTOR ").append(java.util.Arrays.toString(ct.getParameterTypes())).append("\n");
                        }
                        for (java.lang.reflect.Field f2 : cc.getDeclaredFields()) {
                            res.append("  FIELD ").append(f2.getType().getName()).append(" ").append(f2.getName()).append("\n");
                        }
                        for (java.lang.reflect.Method m2 : cc.getDeclaredMethods()) {
                            res.append("  M ").append(m2.getReturnType().getSimpleName()).append(" ").append(m2.getName())
                               .append(java.util.Arrays.toString(m2.getParameterTypes())).append("\n");
                        }
                    } catch (Throwable t) {
                        res.append("  ERR: ").append(truncate(t.toString(), 160)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#ODILISTEN")) {
                    Log.i(TAG, "Command: ODILISTEN");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    StringBuilder events = new StringBuilder();
                    int secs = 20, mode = 0;
                    String[] lp = cmd.trim().split(" ");
                    try {
                        if (lp.length > 1) secs = Integer.parseInt(lp[1]);
                        if (lp.length > 2) mode = Integer.parseInt(lp[2]);
                    } catch (Exception ignored) {}
                    res.append("=== ODI LISTEN ===\n  seconds=").append(secs).append(" mode=").append(mode).append("\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();
                    res = new StringBuilder();
                    ServiceConnection c3 = null;
                    try {
                        final CountDownLatch l3 = new CountDownLatch(1);
                        final IBinder[] s3 = new IBinder[1];
                        c3 = new ServiceConnection() {
                            public void onServiceConnected(ComponentName n, IBinder b) { s3[0] = b; l3.countDown(); }
                            public void onServiceDisconnected(ComponentName n) {}
                        };
                        android.content.Intent i3 = new android.content.Intent();
                        i3.setClassName("com.patac.service", "com.patac.service.PatacService");
                        bindService(i3, c3, BIND_AUTO_CREATE);
                        l3.await(6, java.util.concurrent.TimeUnit.SECONDS);
                        IBinder main3 = s3[0];
                        IBinder sb3 = null;
                        if (main3 != null) {
                            android.os.Parcel dp = android.os.Parcel.obtain();
                            android.os.Parcel rp = android.os.Parcel.obtain();
                            try {
                                dp.writeInterfaceToken("patac.manager.IPatacService");
                                dp.writeString("PatacOdiPropService");
                                main3.transact(1, dp, rp, 0);
                                rp.readException();
                                sb3 = rp.readStrongBinder();
                            } catch (Exception e) {
                                res.append("  step1 err: ").append(truncate(e.toString(), 120)).append("\n");
                            } finally { dp.recycle(); rp.recycle(); }
                        }
                        res.append("  sub=").append(sb3).append("\n");
                        netOut.write(res.toString().getBytes());
                        netOut.flush();
                        res = new StringBuilder();
                        if (sb3 != null) {
                            final android.os.Binder listener3 = new android.os.Binder() {
                                @Override
                                protected boolean onTransact(int c, android.os.Parcel data, android.os.Parcel reply, int flags) {
                                    StringBuilder ev = new StringBuilder();
                                    ev.append("  [cb] code=").append(c).append(" size=").append(data.dataSize()).append("\n");
                                    try {
                                        byte[] raw = data.marshall();
                                        int lim = Math.min(raw.length, 200);
                                        StringBuilder hex = new StringBuilder();
                                        for (int i = 0; i < lim; i++) hex.append(String.format("%02x", raw[i] & 0xFF));
                                        ev.append("    hex=").append(hex).append("\n");
                                    } catch (Exception ignored) {}
                                    events.append(ev);
                                    return true;
                                }
                            };
                            Object wrapper = null;
                            String wErr = null;
                            try {
                                Class<?> wc = Class.forName("patac.manager.odiprop.OdiDataWrapper");
                                wErr = "iface=" + wc.isInterface() + " parcelable=" + android.os.Parcelable.class.isAssignableFrom(wc);
                                if (wc.isInterface()) {
                                    wrapper = java.lang.reflect.Proxy.newProxyInstance(
                                        wc.getClassLoader(), new Class<?>[]{ wc },
                                        new java.lang.reflect.InvocationHandler() {
                                            public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                                                String mn = m.getName();
                                                if (mn.equals("writeToParcel")) {
                                                    try { ((android.os.Parcel) args[0]).writeInt(0); } catch (Exception ignored) {}
                                                    return null;
                                                }
                                                if (mn.equals("describeContents")) return 0;
                                                if (mn.equals("toString")) return "OdiWrapperProxy";
                                                if (mn.equals("hashCode")) return 1;
                                                if (mn.equals("equals")) return proxy == args[0];
                                                return null;
                                            }
                                        });
                                }
                            } catch (Exception e) { wErr = e.toString(); }
                            res.append("  wrapper=").append(wrapper != null ? "ok" : "null").append(" ").append(wErr != null ? wErr : "").append("\n");
                            netOut.write(res.toString().getBytes());
                            netOut.flush();
                            res = new StringBuilder();
                            final IBinder sub3 = sb3;
                            final Object w3 = wrapper;
                            final int mode3 = mode;
                            final boolean[] reg3 = new boolean[1];
                            final String[] rerr3 = new String[1];
                            Thread rt = new Thread(new Runnable() {
                                public void run() {
                                    android.os.Parcel d4 = android.os.Parcel.obtain();
                                    android.os.Parcel r4 = android.os.Parcel.obtain();
                                    try {
                                        d4.writeInterfaceToken("patac.manager.odiprop.IPatacOdiProp");
                                        d4.writeStrongBinder(listener3);
                                        if (w3 != null) {
                                            java.lang.reflect.Method wp = android.os.Parcel.class.getMethod("writeParcelable", android.os.Parcelable.class, int.class);
                                            wp.invoke(d4, w3, 0);
                                        } else {
                                            d4.writeInt(1);
                                            d4.writeInt(0);
                                            d4.writeInt(0);
                                        }
                                        d4.writeInt(mode3);
                                        reg3[0] = sub3.transact(2, d4, r4, 0);
                                        try { r4.readException(); } catch (Exception e) { rerr3[0] = String.valueOf(e.getMessage()); }
                                    } catch (Exception e) {
                                        rerr3[0] = e.toString();
                                    } finally { d4.recycle(); r4.recycle(); }
                                }
                            });
                            rt.setDaemon(true);
                            rt.start();
                            rt.join(4000);
                            res.append("  register ok=").append(reg3[0]).append(" blocked=").append(rt.isAlive()).append(" err=").append(rerr3[0]).append("\n");
                            netOut.write(res.toString().getBytes());
                            netOut.flush();
                            res = new StringBuilder();
                            Thread.sleep(secs * 1000L);
                            res.append("  events bytes=").append(events.length()).append("\n").append(events);
                        }
                    } catch (Exception e) {
                        res.append("  Error: ").append(truncate(e.toString(), 200)).append("\n");
                    } finally {
                        try { if (c3 != null) unbindService(c3); } catch (Exception ignored) {}
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#ODIINFO")) {
                    Log.i(TAG, "Command: ODIINFO");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== ODI INFO ===\n");
                    int a1 = 0, a2 = 0, a3 = 0;
                    String[] ip = cmd.trim().split(" ");
                    try {
                        if (ip.length > 1) a1 = Integer.parseInt(ip[1]);
                        if (ip.length > 2) a2 = Integer.parseInt(ip[2]);
                        if (ip.length > 3) a3 = Integer.parseInt(ip[3]);
                    } catch (Exception ignored) {}
                    res.append("  args=").append(a1).append(",").append(a2).append(",").append(a3).append("\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();
                    res = new StringBuilder();
                    ServiceConnection c2 = null;
                    try {
                        final CountDownLatch l2 = new CountDownLatch(1);
                        final IBinder[] s2 = new IBinder[1];
                        c2 = new ServiceConnection() {
                            public void onServiceConnected(ComponentName n, IBinder b) { s2[0] = b; l2.countDown(); }
                            public void onServiceDisconnected(ComponentName n) {}
                        };
                        android.content.Intent ii = new android.content.Intent();
                        ii.setClassName("com.patac.service", "com.patac.service.PatacService");
                        bindService(ii, c2, BIND_AUTO_CREATE);
                        l2.await(6, java.util.concurrent.TimeUnit.SECONDS);
                        IBinder main = s2[0];
                        IBinder sb = null;
                        if (main != null) {
                            android.os.Parcel dp = android.os.Parcel.obtain();
                            android.os.Parcel rp = android.os.Parcel.obtain();
                            try {
                                dp.writeInterfaceToken("patac.manager.IPatacService");
                                dp.writeString("PatacOdiPropService");
                                main.transact(1, dp, rp, 0);
                                rp.readException();
                                sb = rp.readStrongBinder();
                            } catch (Exception e) {
                                res.append("  step1 err: ").append(truncate(e.toString(), 120)).append("\n");
                            } finally { dp.recycle(); rp.recycle(); }
                        }
                        res.append("  sub=").append(sb).append("\n");
                        netOut.write(res.toString().getBytes());
                        netOut.flush();
                        res = new StringBuilder();
                        if (sb != null) {
                            android.os.Parcel d3 = android.os.Parcel.obtain();
                            android.os.Parcel r3 = android.os.Parcel.obtain();
                            try {
                                d3.writeInterfaceToken("patac.manager.odiprop.IPatacOdiProp");
                                d3.writeInt(a1); d3.writeInt(a2); d3.writeInt(a3);
                                boolean ok = sb.transact(1, d3, r3, 0);
                                res.append("  ok=").append(ok).append("\n");
                                r3.readException();
                                int n = r3.readInt();
                                res.append("  len=").append(n).append("\n");
                                if (n > 0 && n < 4096) {
                                    StringBuilder vb = new StringBuilder();
                                    for (int i = 0; i < n && i < 80; i++) {
                                        try { vb.append(i == 0 ? "" : ",").append(r3.readInt()); }
                                        catch (Exception e2) { vb.append(",ERR"); break; }
                                    }
                                    res.append("  vals=").append(vb).append("\n");
                                }
                            } catch (Exception e) {
                                res.append("  call err: ").append(truncate(e.toString(), 160)).append("\n");
                            } finally { d3.recycle(); r3.recycle(); }
                        }
                    } catch (Exception e) {
                        res.append("  Error: ").append(truncate(e.toString(), 160)).append("\n");
                    } finally {
                        try { if (c2 != null) unbindService(c2); } catch (Exception ignored) {}
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#ODI")) {
                    Log.i(TAG, "Command: ODI");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    StringBuilder events = new StringBuilder();
                    res.append("=== ODI SUBSCRIBE ===\n");
                    int secs = 12, code = 4;
                    String[] op = cmd.trim().split(" ");
                    if (op.length > 1) { try { secs = Integer.parseInt(op[1]); } catch (Exception ignored) {} }
                    if (op.length > 2) { try { code = Integer.parseInt(op[2]); } catch (Exception ignored) {} }
                    res.append("  seconds=").append(secs).append(" code=").append(code).append("\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();
                    res = new StringBuilder();
                    ServiceConnection conn = null;
                    try {
                        final CountDownLatch latch = new CountDownLatch(1);
                        final IBinder[] svc = new IBinder[1];
                        conn = new ServiceConnection() {
                            public void onServiceConnected(ComponentName n, IBinder b) { svc[0] = b; latch.countDown(); }
                            public void onServiceDisconnected(ComponentName n) {}
                        };
                        android.content.Intent oi = new android.content.Intent();
                        oi.setClassName("com.patac.service", "com.patac.service.PatacService");
                        if (!bindService(oi, conn, BIND_AUTO_CREATE)) {
                            res.append("  bind failed\n");
                        } else {
                            latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
                            IBinder s = svc[0];
                            res.append("  svc=").append(s).append("\n");
                            netOut.write(res.toString().getBytes()); netOut.flush(); res = new StringBuilder();
                            IBinder sub = null;
                            if (s != null) {
                                android.os.Parcel d1 = android.os.Parcel.obtain();
                                android.os.Parcel r1 = android.os.Parcel.obtain();
                                try {
                                    d1.writeInterfaceToken("patac.manager.IPatacService");
                                    d1.writeString("PatacOdiPropService");
                                    s.transact(1, d1, r1, 0);
                                    r1.readException();
                                    sub = r1.readStrongBinder();
                                } catch (Exception e) {
                                    res.append("  step1: ").append(truncate(e.toString(), 120)).append("\n");
                                } finally { d1.recycle(); r1.recycle(); }
                            }
                            res.append("  sub=").append(sub).append("\n");
                            netOut.write(res.toString().getBytes()); netOut.flush(); res = new StringBuilder();
                            if (sub != null) {
                                android.os.Binder listener = new android.os.Binder() {
                                    @Override
                                    protected boolean onTransact(int c, android.os.Parcel data, android.os.Parcel reply, int flags) {
                                        StringBuilder ev = new StringBuilder();
                                        ev.append("  [cb] code=").append(c).append(" avail=").append(data.dataAvail()).append("\n");
                                        try { String ifc = data.readString(); ev.append("    iface=").append(ifc).append("\n"); } catch (Exception ignored) {}
                                        try { int n = data.readInt(); ev.append("    int=").append(n).append("\n"); } catch (Exception ignored) {}
                                        try { String sv2 = data.readString(); ev.append("    str=").append(sv2).append("\n"); } catch (Exception ignored) {}
                                        try {
                                            int av = data.dataAvail();
                                            if (av > 0) {
                                                byte[] rest = new byte[Math.min(av, 64)];
                                                data.readByteArray(rest);
                                                StringBuilder hex = new StringBuilder();
                                                for (byte bb : rest) hex.append(String.format("%02x ", bb & 0xFF));
                                                ev.append("    hex=").append(hex).append("\n");
                                            }
                                        } catch (Exception ignored) {}
                                        events.append(ev);
                                        return true;
                                    }
                                };
                                final IBinder subB = sub;
                                final int regCode = code;
                                final boolean[] regDone = new boolean[1];
                                final String[] regErr = new String[1];
                                Thread regT = new Thread(new Runnable() {
                                    public void run() {
                                        android.os.Parcel d2 = android.os.Parcel.obtain();
                                        android.os.Parcel r2 = android.os.Parcel.obtain();
                                        try {
                                            d2.writeInterfaceToken("patac.manager.odiprop.IPatacOdiProp");
                                            d2.writeStrongBinder(listener);
                                            regDone[0] = subB.transact(regCode, d2, r2, 0);
                                            try { r2.readException(); } catch (Exception e) {
                                                regErr[0] = String.valueOf(e.getMessage());
                                            }
                                        } catch (Exception e) {
                                            regErr[0] = e.toString();
                                        } finally { d2.recycle(); r2.recycle(); }
                                    }
                                });
                                regT.setDaemon(true);
                                regT.start();
                                regT.join(4000);
                                res.append("  register ok=").append(regDone[0])
                                   .append(" blocked=").append(regT.isAlive())
                                   .append(" err=").append(regErr[0]).append("\n");
                                netOut.write(res.toString().getBytes()); netOut.flush(); res = new StringBuilder();
                                Thread.sleep(secs * 1000L);
                                res.append("  events bytes=").append(events.length()).append("\n").append(events);
                            }
                        }
                    } catch (Exception e) {
                        res.append("  Error: ").append(truncate(e.toString(), 200)).append("\n");
                    } finally {
                        try { if (conn != null) unbindService(conn); } catch (Exception ignored) {}
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#BIND ")) {
                    String rest = firstLine.trim().substring(6).trim();
                    Log.i(TAG, "Command: BIND " + rest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== BIND SERVICE: ").append(rest).append(" ===\n");
                    String[] parts = rest.split(" ");
                    String svcName = parts[0];
                    int code = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                    
                    // Parse args after --
                    java.util.ArrayList<String> args = new java.util.ArrayList<>();
                    boolean argMode = false;
                    for (int ai = 2; ai < parts.length; ai++) {
                        if (parts[ai].equals("--")) {
                            argMode = true;
                        } else if (argMode) {
                            args.add(parts[ai]);
                        }
                    }
                    
                    try {
                        Class<?> smClass = Class.forName("android.os.ServiceManager");
                        java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
                        android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, svcName);
                        
                        if (binder == null) {
                            res.append("Service not found (returned null)\n");
                        } else {
                            res.append("Got Binder: ").append(binder).append("\n");
                            res.append("Interface: ").append(binder.getInterfaceDescriptor()).append("\n");
                            res.append("Ping: ").append(binder.pingBinder()).append("\n");
                            
                            // Transact with requested code
                            android.os.Parcel data = android.os.Parcel.obtain();
                            android.os.Parcel reply = android.os.Parcel.obtain();
                            try {
                                data.writeInterfaceToken(binder.getInterfaceDescriptor());
                                for (String a : args) {
                                    if (a.startsWith("s:")) {
                                        data.writeString(a.substring(2));
                                    } else if (a.startsWith("i:")) {
                                        data.writeInt(Integer.parseInt(a.substring(2)));
                                    } else if (a.startsWith("l:")) {
                                        data.writeLong(Long.parseLong(a.substring(2)));
                                    } else if (a.startsWith("b:")) {
                                        data.writeByte(Byte.parseByte(a.substring(2)));
                                    }
                                }
                                boolean ok = binder.transact(code, data, reply, 0);
                                res.append("transact(" + code + "): ").append(ok).append("\n");
                                if (ok) {
                                    int totalAvail = reply.dataAvail();
                                    res.append("  Reply size: ").append(totalAvail).append(" bytes\n");
                                    
                                    // Hex dump raw bytes from position 0 (may fail if Binder objects are in reply)
                                    reply.setDataPosition(0);
                                    boolean hasBinderObjects = false;
                                    if (totalAvail > 0) {
                                        try {
                                            byte[] raw = reply.marshall();
                                            int limit = Math.min(raw.length, 256);
                                            StringBuilder hex = new StringBuilder();
                                            StringBuilder ascii = new StringBuilder();
                                            for (int i = 0; i < limit; i++) {
                                                if (i % 16 == 0 && i > 0) {
                                                    hex.append("  ").append(ascii).append("\n");
                                                    ascii = new StringBuilder();
                                                }
                                                int val = raw[i] & 0xFF;
                                                hex.append(String.format("%02x ", val));
                                                ascii.append(val >= 32 && val <= 126 ? (char)val : '.');
                                            }
                                            if (ascii.length() > 0) {
                                                int pad = Math.max(0, (16 - (limit % 16)) * 3);
                                                for (int i = 0; i < pad; i++) hex.append(' ');
                                                hex.append("  ").append(ascii);
                                            }
                                            res.append("  HEX (" + limit + "/" + raw.length + " bytes):\n");
                                            res.append(hex).append("\n");
                                        } catch (RuntimeException e) {
                                            if (e.getMessage() != null && e.getMessage().contains("marshall a Parcel that contained Binder objects")) {
                                                hasBinderObjects = true;
                                                res.append("  ⚠️ Reply contains Binder objects (can't hex dump)\n");
                                            } else {
                                                res.append("  ⚠️ Marshall error: ").append(truncate(e.getMessage(), 60)).append("\n");
                                            }
                                        }
                                        
                                        // Try to read Exception from Parcel
                                        reply.setDataPosition(0);
                                        try {
                                            reply.readException();
                                            res.append("  Exception: none\n");
                                        } catch (Exception e) {
                                            res.append("  Exception: ").append(truncate(e.toString(), 40)).append("\n");
                                            reply.setDataPosition(0);
                                        }
                                        
                                        int avail = reply.dataAvail();
                                        res.append("  Available after exc: ").append(avail).append(" bytes\n");
                                        
                                        // Try reading Binder from reply (especially useful for BinderPool/queryBinder)
                                        reply.setDataPosition(0);
                                        try {
                                            reply.readException(); // skip exception
                                        } catch (Exception e) {
                                            reply.setDataPosition(0);
                                        }
                                        try {
                                            android.os.IBinder b = reply.readStrongBinder();
                                            if (b != null) {
                                                res.append("  Binder: ").append(b).append("\n");
                                                res.append("    iface: ").append(b.getInterfaceDescriptor()).append("\n");
                                                res.append("    ping: ").append(b.pingBinder()).append("\n");
                                                
                                                // If this is a sub-binder, try transact on it too
                                                if (hasBinderObjects) {
                                                    res.append("  \n  --- SUB-BINDER TRANSACT (code 1) ---\n");
                                                    android.os.Parcel subData = android.os.Parcel.obtain();
                                                    android.os.Parcel subReply = android.os.Parcel.obtain();
                                                    try {
                                                        subData.writeInterfaceToken(b.getInterfaceDescriptor());
                                                        boolean subOk = b.transact(1, subData, subReply, 0);
                                                        res.append("  sub transact(1): ").append(subOk).append("\n");
                                                        if (subOk && subReply.dataAvail() > 0) {
                                                            subReply.setDataPosition(0);
                                                            try {
                                                                subReply.readException();
                                                            } catch (Exception e) {}
                                                            subReply.setDataPosition(0);
                                                            android.os.IBinder subB = subReply.readStrongBinder();
                                                            if (subB != null) {
                                                                res.append("  Sub-Binder: ").append(subB).append("\n");
                                                                res.append("    iface: ").append(subB.getInterfaceDescriptor()).append("\n");
                                                            }
                                                        }
                                                    } catch (Exception e) {
                                                        res.append("  sub transact error: ").append(truncate(e.toString(), 60)).append("\n");
                                                    } finally {
                                                        subData.recycle();
                                                        subReply.recycle();
                                                    }
                                                }
                                            } else {
                                                res.append("  Binder: null (no binder in reply)\n");
                                            }
                                        } catch (Exception e) {
                                            res.append("  Binder read error: ").append(truncate(e.toString(), 40)).append("\n");
                                        }
                                    } else {
                                        res.append("  (empty reply parcel)\n");
                                    }
                                }
                            } catch (SecurityException e) {
                                res.append("SECURITY: ").append(e.getMessage()).append("\n");
                            } catch (Exception e) {
                                res.append("transact error: ").append(truncate(e.toString(), 200)).append("\n");
                            } finally {
                                data.recycle();
                                reply.recycle();
                            }
                        }
                    } catch (ClassNotFoundException e) {
                        res.append("ServiceManager class not found\n");
                    } catch (NoSuchMethodException e) {
                        res.append("getService method not found\n");
                    } catch (Exception e) {
                        res.append("Error: ").append(truncate(e.toString(), 300)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#CAR ")) {
                    String carRest = firstLine.trim().substring(5).trim();
                    Log.i(TAG, "Command: CAR " + carRest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== CAR SERVICE: ").append(carRest).append(" ===\n");
                    
                    String[] carParts = carRest.split(" ");
                    String carSvcName = carParts[0];
                    int carCode = carParts.length > 1 ? Integer.parseInt(carParts[1]) : 1;
                    
                    try {
                        Class<?> smClass = Class.forName("android.os.ServiceManager");
                        java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
                        android.os.IBinder carBinder = (android.os.IBinder) getService.invoke(null, "car_service");
                        
                        if (carBinder == null) {
                            res.append("car_service not found\n");
                        } else {
                            res.append("Got ICar: ").append(carBinder).append("\n");
                            res.append("Iface: ").append(carBinder.getInterfaceDescriptor()).append("\n");
                            
                            final java.util.concurrent.CountDownLatch carLatch = new java.util.concurrent.CountDownLatch(1);
                            final IBinder[] carResult = new IBinder[1];
                            final String[] carError = new String[1];
                            // Strong reference to prevent GC
                            final android.os.Binder carCallback = new android.os.Binder("com.<PRIVATE_DOMAIN>.os.IResultReceiver") {
                                @Override
                                protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                                    if (code == android.os.IBinder.FIRST_CALL_TRANSACTION) {
                                        try {
                                            // IResultReceiver.send(int resultCode, Bundle resultData)
                                            int resultCode = data.readInt();
                                            android.os.Bundle resultData = null;
                                            if (data.readInt() != 0) {
                                                resultData = android.os.Bundle.CREATOR.createFromParcel(data);
                                            }
                                            res.append("  Callback fired! code=" + resultCode + "\n");
                                            if (resultData != null) {
                                                res.append("  Bundle keys: " + resultData.keySet() + "\n");
                                                for (String key : resultData.keySet()) {
                                                    Object val = resultData.get(key);
                                                    if (val instanceof android.os.IBinder) {
                                                        carResult[0] = (android.os.IBinder) val;
                                                        res.append("  Got service Binder from Bundle!\n");
                                                    }
                                                    String s = (val == null) ? "null" : val.toString();
                                                    if (s.length() > 120) s = s.substring(0, 120) + "...";
                                                    res.append("  [" + key + "] = " + s + "\n");
                                                }
                                            } else {
                                                res.append("  Bundle: null\n");
                                            }
                                        } catch (Exception e) {
                                            carError[0] = truncate(e.toString(), 80);
                                        }
                                        carLatch.countDown();
                                        return true;
                                    }
                                    return false;
                                }
                            };
                            
                            android.os.Parcel carData = android.os.Parcel.obtain();
                            android.os.Parcel carReply = android.os.Parcel.obtain();
                            try {
                                // Try format 1: service name only (no callback)
                                carData.writeInterfaceToken("android.car.ICar");
                                carData.writeString(carSvcName);
                                
                                boolean ok = carBinder.transact(1, carData, carReply, 0);
                                res.append("transact(1) no-cb: " + ok + "\n");
                                
                                if (!ok || carReply.dataAvail() == 0) {
                                    // Try format 2: string + binder
                                    carData.setDataPosition(0);
                                    carData.writeInterfaceToken("android.car.ICar");
                                    carData.writeString(carSvcName);
                                    carData.writeStrongBinder(carCallback);
                                    ok = carBinder.transact(1, carData, carReply, 0);
                                    res.append("transact(1) w-cb: " + ok + "\n");
                                }
                                
                                if (ok) {
                                    // Check reply parcel for direct return value
                                    int rAvail = carReply.dataAvail();
                                    res.append("  Reply avail: " + rAvail + " bytes\n");
                                    if (rAvail > 0) {
                                        // Try to parse reply: exception + return value
                                        try {
                                            carReply.setDataPosition(0);
                                            int excCode = carReply.readInt();
                                            res.append("  excCode: " + excCode + "\n");
                                            if (excCode == 0) {
                                                // No exception, try to read Binder
                                                IBinder directBinder = carReply.readStrongBinder();
                                                if (directBinder != null) {
                                                    carResult[0] = directBinder;
                                                    res.append("  Got service Binder from reply!\n");
                                                    res.append("  Binder: " + directBinder + "\n");
                                                    try {
                                                        res.append("  Iface: " + directBinder.getInterfaceDescriptor() + "\n");
                                                    } catch (Exception ignored) {}
                                                } else {
                                                    res.append("  readStrongBinder returned null\n");
                                                    // Try reading as int
                                                    carReply.setDataPosition(4);
                                                    int retVal = carReply.readInt();
                                                    res.append("  retVal(int): " + retVal + "\n");
                                                }
                                            } else {
                                                // Exception, read message
                                                String excMsg = carReply.readString();
                                                res.append("  Exception: " + excMsg + "\n");
                                            }
                                        } catch (Exception e) {
                                            res.append("  Parse error: " + truncate(e.toString(), 120) + "\n");
                                        }
                                    }
                                    
                                    // Also wait for async callback
                                    boolean timedOut = !carLatch.await(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
                                    if (timedOut && carResult[0] == null) {
                                        res.append("  No callback fired (3s)\n");
                                    }
                                    
                                    if (carResult[0] != null) {
                                        IBinder svcBinder = carResult[0];
                                        res.append("  Got service Binder!\n");
                                        res.append("  Iface: " + svcBinder.getInterfaceDescriptor() + "\n");
                                        res.append("  Ping: " + svcBinder.pingBinder() + "\n");
                                        
                                        if (carParts.length > 1) {
                                            android.os.Parcel svcData = android.os.Parcel.obtain();
                                            android.os.Parcel svcReply = android.os.Parcel.obtain();
                                            try {
                                                svcData.writeInterfaceToken(svcBinder.getInterfaceDescriptor());
                                                boolean svcOk = svcBinder.transact(carCode, svcData, svcReply, 0);
                                                res.append("  svc tr(" + carCode + "): " + svcOk + "\n");
                                                if (svcOk && svcReply.dataAvail() > 0) {
                                                    try {
                                                        svcReply.setDataPosition(4);
                                                        int iv = svcReply.readInt();
                                                        res.append("  -> Int: " + iv + "\n");
                                                    } catch (Exception e) {}
                                                    try {
                                                        svcReply.setDataPosition(4);
                                                        String s = svcReply.readString();
                                                        if (s != null) res.append("  -> String: " + s + "\n");
                                                    } catch (Exception e) {}
                                                }
                                            } finally {
                                                svcData.recycle();
                                                svcReply.recycle();
                                            }
                                        }
                                    } else if (carError[0] != null) {
                                        res.append("  Error: " + carError[0] + "\n");
                                    } else {
                                        res.append("  No service returned\n");
                                    }
                                }
                            } catch (Exception e) {
                                res.append("Error: " + truncate(e.toString(), 200) + "\n");
                            } finally {
                                carData.recycle();
                                carReply.recycle();
                            }
                        }
                    } catch (Exception e) {
                        res.append("Error: " + truncate(e.toString(), 300) + "\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#NAV ") || cmd.equals("#NAV")) {
                    String navRest = firstLine.trim().substring(5).trim();
                    Log.i(TAG, "Command: NAV " + navRest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== NAV SERVICE ===\n");
                    
                    String[] navParts = navRest.length() > 0 ? navRest.split(" ") : new String[0];
                    String targetPkg = "com.sgm.navi.hmi";
                    String targetCls = "com.sgm.navi.exportservice.MapService";
                    int navCode = 1;
                    
                    if (navParts.length >= 1 && !navParts[0].isEmpty()) {
                        try {
                            navCode = Integer.parseInt(navParts[0]);
                        } catch (NumberFormatException e) {
                            if (navParts[0].contains("/")) {
                                String[] split = navParts[0].split("/");
                                targetPkg = split[0];
                                targetCls = split[1];
                                if (navParts.length > 1) {
                                    try { navCode = Integer.parseInt(navParts[1]); } catch (NumberFormatException e2) {}
                                }
                            }
                        }
                    }
                    
                    final int[] finalCode = new int[]{navCode};
                    final CountDownLatch latch = new CountDownLatch(1);
                    final android.os.IBinder[] resultBinder = new android.os.IBinder[1];
                    final String[] resultError = new String[1];
                    
                    try {
                        Intent intent = new Intent();
                        intent.setClassName(targetPkg, targetCls);
                        
                        ServiceConnection conn = new ServiceConnection() {
                            @Override
                            public void onServiceConnected(ComponentName name, android.os.IBinder service) {
                                resultBinder[0] = service;
                                latch.countDown();
                            }
                            @Override
                            public void onServiceDisconnected(ComponentName name) {
                                resultError[0] = "Service disconnected";
                                latch.countDown();
                            }
                            @Override
                            public void onBindingDied(ComponentName name) {
                                resultError[0] = "Binding died";
                                latch.countDown();
                            }
                            @Override
                            public void onNullBinding(ComponentName name) {
                                resultError[0] = "Null binding";
                                latch.countDown();
                            }
                        };
                        
                        boolean bound = bindService(intent, conn, BIND_AUTO_CREATE);
                        res.append("Intent: ").append(intent).append("\n");
                        res.append("bindService: ").append(bound ? "OK" : "FAILED").append("\n");
                        
                        if (bound) {
                            boolean waited = latch.await(5, TimeUnit.SECONDS);
                            if (!waited) {
                                res.append("  Timeout: no callback within 5s\n");
                            } else if (resultError[0] != null) {
                                res.append("  Error: ").append(resultError[0]).append("\n");
                            } else if (resultBinder[0] != null) {
                                android.os.IBinder binder = resultBinder[0];
                                res.append("Got Binder: ").append(binder).append("\n");
                                res.append("Ping: ").append(binder.pingBinder()).append("\n");
                                try {
                                    String iface = binder.getInterfaceDescriptor();
                                    res.append("Interface: ").append(iface).append("\n");
                                } catch (Exception e) {
                                    res.append("Interface: (can\'t query)\n");
                                }
                                
                                // Try transact with the requested code
                                android.os.Parcel data = android.os.Parcel.obtain();
                                android.os.Parcel reply = android.os.Parcel.obtain();
                                try {
                                    String actualIface = binder.getInterfaceDescriptor();
                                    if (actualIface == null) actualIface = "com.sgm.navi.navigation.INavigationService";
                                    data.writeInterfaceToken(actualIface);
                                    boolean ok = binder.transact(finalCode[0], data, reply, 0);
                                    res.append("transact(" + finalCode[0] + "): ").append(ok).append("\n");
                                    if (ok) {
                                        int avail = reply.dataAvail();
                                        res.append("  Reply: ").append(avail).append(" bytes\n");
                                        if (avail > 0) {
                                            reply.setDataPosition(0);
                                            String hexDump;
                                            try {
                                                byte[] raw = reply.marshall();
                                                int limit = Math.min(raw.length, 128);
                                                StringBuilder hex = new StringBuilder();
                                                for (int i = 0; i < limit; i++) {
                                                    hex.append(String.format("%02x ", raw[i] & 0xFF));
                                                }
                                                hexDump = hex.toString();
                                            } catch (RuntimeException e) {
                                                hexDump = "(contains Binder objects, can't marshall)";
                                            }
                                            res.append("  HEX: ").append(hexDump).append("\n");
                                            
                                            // Try reading Binder from reply (for BinderPool)
                                            reply.setDataPosition(0);
                                            try {
                                                reply.readException();
                                                android.os.IBinder subB = reply.readStrongBinder();
                                                if (subB != null) {
                                                    res.append("  Sub-Binder: ").append(subB).append("\n");
                                                    res.append("    iface: ").append(subB.getInterfaceDescriptor()).append("\n");
                                                }
                                            } catch (Exception e) {
                                                // Try reading string values instead
                                                reply.setDataPosition(0);
                                                try {
                                                    int exc = reply.readInt();
                                                    if (exc != 0) {
                                                        res.append("  Exception code: ").append(exc).append("\n");
                                                    }
                                                    if (avail > 8) {
                                                        int strCount = avail / 80;
                                                        for (int si = 0; si < Math.min(strCount, 20); si++) {
                                                            try {
                                                                int pos = reply.dataPosition();
                                                                String s = reply.readString();
                                                                res.append("  Str[" + si + "@" + pos + "]: ").append(s).append("\n");
                                                            } catch (Exception e2) { break; }
                                                        }
                                                    }
                                                } catch (Exception e3) {
                                                    reply.setDataPosition(4);
                                                    try {
                                                        res.append("  First int: ").append(reply.readInt()).append("\n");
                                                    } catch (Exception e4) {}
                                                }
                                            }
                                        }
                                    }
                                } catch (SecurityException e) {
                                    res.append("  SECURITY: ").append(e.getMessage()).append("\n");
                                } catch (Exception e) {
                                    res.append("  transact error: ").append(truncate(e.toString(), 100)).append("\n");
                                } finally {
                                    data.recycle();
                                    reply.recycle();
                                }
                            }
                            // Unbind to clean up
                            try { unbindService(conn); } catch (Exception e) {}
                        }
                    } catch (SecurityException e) {
                        res.append("SECURITY: ").append(e.getMessage()).append("\n");
                    } catch (Exception e) {
                        res.append("Error: ").append(truncate(e.toString(), 300)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#SETPROP ")) {
                    String setRest = firstLine.trim().substring(9).trim();
                    Log.i(TAG, "Command: SETPROP " + setRest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== SETPROP: ").append(setRest).append(" ===\n");
                    String[] setParts = setRest.split(" ", 2);
                    if (setParts.length < 2) {
                        res.append("Usage: #SETPROP <name> <value>\n");
                    } else {
                        String propName = setParts[0];
                        String propValue = setParts[1];
                        try {
                            Class<?> spClass = Class.forName("android.os.SystemProperties");
                            java.lang.reflect.Method setMethod = spClass.getMethod("set", String.class, String.class);
                            setMethod.invoke(null, propName, propValue);
                            res.append("  OK: ").append(propName).append(" = ").append(propValue).append("\n");
                            // Verify
                            java.lang.reflect.Method getMethod = spClass.getMethod("get", String.class, String.class);
                            String verify = (String) getMethod.invoke(null, propName, "");
                            res.append("  Verify: ").append(propName).append(" = ").append(verify).append("\n");
                        } catch (SecurityException e) {
                            res.append("  SECURITY: ").append(truncate(e.toString(), 100)).append("\n");
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            res.append("  ITE cause: ").append(e.getCause()).append("\n");
                            res.append("  msg: ").append(truncate(e.getCause() != null ? e.getCause().getMessage() : "null", 200)).append("\n");
                        } catch (Exception e) {
                            res.append("  Error: ").append(truncate(e.toString(), 150)).append("\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#PERMS")) {
                    Log.i(TAG, "Command: PERMS");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== PERMISSION STATUS ===\n");
                    String[][] perms = {
                        {"RECORD_AUDIO", Manifest.permission.RECORD_AUDIO},
                        {"ACCESS_FINE_LOCATION", Manifest.permission.ACCESS_FINE_LOCATION},
                        {"ACCESS_COARSE_LOCATION", Manifest.permission.ACCESS_COARSE_LOCATION},
                        {"CAMERA", Manifest.permission.CAMERA},
                        {"READ_EXTERNAL_STORAGE", Manifest.permission.READ_EXTERNAL_STORAGE},
                        {"WRITE_EXTERNAL_STORAGE", Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    };
                    java.util.List<String[]> dynPerms = new java.util.ArrayList<>();
                    java.util.Collections.addAll(dynPerms, perms);
                    if (Build.VERSION.SDK_INT >= 31) {
                        dynPerms.add(new String[]{"BLUETOOTH_CONNECT", Manifest.permission.BLUETOOTH_CONNECT});
                        dynPerms.add(new String[]{"BLUETOOTH_SCAN", Manifest.permission.BLUETOOTH_SCAN});
                    } else {
                        dynPerms.add(new String[]{"BLUETOOTH", Manifest.permission.BLUETOOTH});
                        dynPerms.add(new String[]{"BLUETOOTH_ADMIN", Manifest.permission.BLUETOOTH_ADMIN});
                    }
                    for (String[] p : dynPerms) {
                        String name = p[0];
                        String perm = p[1];
                        int result = checkSelfPermission(perm);
                        String status = (result == PackageManager.PERMISSION_GRANTED) ? "✅ GRANTED" : "❌ DENIED";
                        res.append("  ").append(status).append("  ").append(name).append("\n");
                    }
                    // Also check location mode
                    try {
                        int locMode = android.provider.Settings.Secure.getInt(
                            getContentResolver(), "location_mode", 0);
                        String[] modes = {"OFF", "SENSORS_ONLY", "BATTERY_SAVING", "HIGH_ACCURACY"};
                        String modeStr = (locMode >= 0 && locMode < modes.length) ? modes[locMode] : "UNKNOWN("+locMode+")";
                        res.append("  📡 Location Mode: ").append(modeStr).append("\n");
                    } catch (Exception e) {
                        res.append("  📡 Location Mode: ").append(truncate(e.toString(), 40)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#GPS") || cmd.startsWith("#GPS ")) {
                    Log.i(TAG, "Command: GPS");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== GPS LOCATION ===\n");
                    try {
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                != PackageManager.PERMISSION_GRANTED) {
                            res.append("  ❌ ACCESS_FINE_LOCATION not granted\n");
                        } else {
                            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                            if (lm == null) {
                                res.append("  ❌ LocationManager not available\n");
                            } else {
                                // Try GPS provider first
                                Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                                if (loc == null) {
                                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                                }
                                if (loc == null) {
                                    loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                                }
                                if (loc != null) {
                                    res.append("  ✅ Got cached location:\n");
                                    res.append("    Provider: ").append(loc.getProvider()).append("\n");
                                    res.append("    Lat: ").append(loc.getLatitude()).append("\n");
                                    res.append("    Lon: ").append(loc.getLongitude()).append("\n");
                                    res.append("    Accuracy: ").append(loc.getAccuracy()).append("m\n");
                                    res.append("    Time: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                        java.util.Locale.CHINA).format(new java.util.Date(loc.getTime()))).append("\n");
                                    if (loc.hasAltitude()) {
                                        res.append("    Altitude: ").append(loc.getAltitude()).append("m\n");
                                    }
                                    if (loc.hasSpeed()) {
                                        res.append("    Speed: ").append(loc.getSpeed()).append(" m/s\n");
                                    }
                                    if (loc.hasBearing()) {
                                        res.append("    Bearing: ").append(loc.getBearing()).append("°\n");
                                    }
                                } else {
                                    res.append("  ⏳ No cached location. Requesting fresh fix...\n");
                                    // Try to request single update with timeout
                                    final CountDownLatch locLatch = new CountDownLatch(1);
                                    final AtomicReference<Location> freshLoc = new AtomicReference<>(null);
                                    LocationListener listener = new LocationListener() {
                                        @Override
                                        public void onLocationChanged(Location l) {
                                            freshLoc.set(l);
                                            locLatch.countDown();
                                        }
                                        @Override
                                        public void onStatusChanged(String p, int s, Bundle b) {}
                                        @Override
                                        public void onProviderEnabled(String p) {}
                                        @Override
                                        public void onProviderDisabled(String p) { locLatch.countDown(); }
                                    };
                                    Criteria criteria = new Criteria();
                                    criteria.setAccuracy(Criteria.ACCURACY_FINE);
                                    String bestProvider = lm.getBestProvider(criteria, true);
                                    res.append("    Best provider: ").append(bestProvider != null ? bestProvider : "none").append("\n");
                                    if (bestProvider != null) {
                                        android.os.HandlerThread locThread = new android.os.HandlerThread("gps-fix");
                                        locThread.start();
                                        android.os.Looper locLooper = locThread.getLooper();
                                        lm.requestSingleUpdate(bestProvider, listener, locLooper);
                                        boolean gotFix = locLatch.await(10, TimeUnit.SECONDS);
                                        lm.removeUpdates(listener);
                                        locThread.quitSafely();
                                        Location fresh = freshLoc.get();
                                        if (fresh != null) {
                                            res.append("  ✅ Fresh fix:\n");
                                            res.append("    Provider: ").append(fresh.getProvider()).append("\n");
                                            res.append("    Lat: ").append(fresh.getLatitude()).append("\n");
                                            res.append("    Lon: ").append(fresh.getLongitude()).append("\n");
                                            res.append("    Accuracy: ").append(fresh.getAccuracy()).append("m\n");
                                        } else {
                                            res.append("  ❌ No fix within 10s (maybe GPS indoors)\n");
                                        }
                                    } else {
                                        res.append("  ❌ No location provider available\n");
                                    }
                                }
                            }
                        }
                    } catch (SecurityException e) {
                        res.append("  ❌ SecurityException: ").append(truncate(e.toString(), 100)).append("\n");
                    } catch (Exception e) {
                        res.append("  ❌ Error: ").append(truncate(e.toString(), 200)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#MIC") || cmd.startsWith("#MIC ")) {
                    Log.i(TAG, "Command: MIC");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== MICROPHONE TEST ===\n");
                    try {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED) {
                            res.append("  ❌ RECORD_AUDIO not granted\n");
                        } else {
                            // Parse duration (default 5s)
                            int durationMs = 5000;
                            String rest = firstLine.trim().substring(4).trim();
                            if (!rest.isEmpty()) {
                                try { durationMs = Integer.parseInt(rest) * 1000; } catch (Exception e) {}
                            }
                            if (durationMs > 30000) durationMs = 30000;
                            if (durationMs < 1000) durationMs = 1000;

                            File outFile = new File(getFilesDir(), "test_recording.3gp");
                            if (outFile.exists()) outFile.delete();

                            MediaRecorder recorder = new MediaRecorder();
                            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                            recorder.setAudioSamplingRate(8000);
                            recorder.setOutputFile(outFile.getAbsolutePath());
                            recorder.prepare();
                            recorder.start();
                            res.append("  🎤 Recording for ").append(durationMs / 1000).append("s...\n");

                            // Wait for recording
                            long startMs = System.currentTimeMillis();
                            while (System.currentTimeMillis() - startMs < durationMs) {
                                try { Thread.sleep(100); } catch (InterruptedException e) {}
                            }

                            recorder.stop();
                            recorder.release();

                            long fileSize = outFile.length();
                            res.append("  ✅ Recording complete!\n");
                            res.append("    File: ").append(outFile.getAbsolutePath()).append("\n");
                            res.append("    Size: ").append(fileSize).append(" bytes\n");
                            if (fileSize > 1000) {
                                res.append("    Status: 🎤 MICROPHONE WORKS!\n");
                            } else {
                                res.append("    Status: ⚠️ File too small, mic might not have captured audio\n");
                            }
                        }
                    } catch (SecurityException e) {
                        res.append("  ❌ SecurityException: ").append(truncate(e.toString(), 100)).append("\n");
                    } catch (Exception e) {
                        res.append("  ❌ Error: ").append(truncate(e.toString(), 200)).append("\n");
                        java.io.StringWriter sw = new java.io.StringWriter();
                        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                        e.printStackTrace(pw);
                        String trace = sw.toString();
                        res.append("  Trace: ").append(trace.substring(0, Math.min(500, trace.length()))).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#CAM") || cmd.startsWith("#CAM ")) {
                    Log.i(TAG, "Command: CAM");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== CAMERA TEST ===\n");
                    try {
                        if (checkSelfPermission(Manifest.permission.CAMERA)
                                != PackageManager.PERMISSION_GRANTED) {
                            res.append("  ❌ CAMERA not granted\n");
                        } else {
                            res.append("  Trying Camera.open()...\n");
                            int cameraCount = android.hardware.Camera.getNumberOfCameras();
                            res.append("  Camera count: ").append(cameraCount).append("\n");

                            if (cameraCount > 0) {
                                for (int i = 0; i < cameraCount; i++) {
                                    android.hardware.Camera.CameraInfo info =
                                        new android.hardware.Camera.CameraInfo();
                                    android.hardware.Camera.getCameraInfo(i, info);
                                    String facing = (info.facing ==
                                        android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK)
                                        ? "BACK" : "FRONT";
                                    res.append("  Camera #").append(i).append(": ").append(facing)
                                       .append(" orientation=").append(info.orientation).append("°\n");
                                }

                                // Try to open camera 0 and take a picture
                                res.append("  Opening camera 0...\n");
                                try {
                                    final android.hardware.Camera camera =
                                        android.hardware.Camera.open(0);
                                    res.append("  ✅ Camera opened!\n");

                                    android.hardware.Camera.Parameters params =
                                        camera.getParameters();
                                    res.append("  Picture size: ");
                                    for (android.hardware.Camera.Size sz :
                                            params.getSupportedPictureSizes()) {
                                        if (sz.width >= 640) {
                                            res.append(sz.width).append("x").append(sz.height).append(" ");
                                        }
                                    }
                                    res.append("\n");

                                    // Take a picture
                                    File photoFile = new File(getFilesDir(), "test_photo.jpg");
                                    res.append("  Taking photo...\n");
                                    final CountDownLatch photoLatch = new CountDownLatch(1);
                                    final byte[][] photoData = new byte[1][];

                                    android.hardware.Camera.PictureCallback jpegCallback =
                                        new android.hardware.Camera.PictureCallback() {
                                        @Override
                                        public void onPictureTaken(byte[] data,
                                                android.hardware.Camera camera) {
                                            photoData[0] = data;
                                            photoLatch.countDown();
                                        }
                                    };

                                    camera.takePicture(null, null, jpegCallback);
                                    boolean gotPhoto = photoLatch.await(5, TimeUnit.SECONDS);

                                    if (gotPhoto && photoData[0] != null) {
                                        java.io.FileOutputStream fos =
                                            new java.io.FileOutputStream(photoFile);
                                        fos.write(photoData[0]);
                                        fos.close();
                                        res.append("  ✅ Photo saved!\n");
                                        res.append("    File: ").append(photoFile.getAbsolutePath()).append("\n");
                                        res.append("    Size: ").append(photoData[0].length).append(" bytes\n");
                                    } else {
                                        res.append("  ⚠️ No photo data received (timeout)\n");
                                    }

                                    camera.release();
                                    res.append("  Camera released\n");
                                } catch (Exception e) {
                                    res.append("  ❌ Camera open/take failed: ")
                                       .append(truncate(e.toString(), 150)).append("\n");
                                }
                            } else {
                                res.append("  No cameras found on this device\n");
                            }
                        }
                    } catch (SecurityException e) {
                        res.append("  ❌ SecurityException: ").append(truncate(e.toString(), 100)).append("\n");
                    } catch (Exception e) {
                        res.append("  ❌ Error: ").append(truncate(e.toString(), 200)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#READ ")) {
                    String filePath = firstLine.trim().substring(6).trim();
                    Log.i(TAG, "Command: READ " + filePath);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== READ: ").append(filePath).append(" ===\n");
                    try {
                        File f = new File(filePath);
                        if (!f.exists()) {
                            res.append("  ❌ File not found\n");
                        } else if (!f.canRead()) {
                            res.append("  ❌ Permission denied\n");
                        } else {
                            long len = f.length();
                            res.append("  Size: ").append(len).append(" bytes\n");
                            res.append("\n");
                            // Read text content (first 200KB)
                            BufferedReader fr = new BufferedReader(new java.io.FileReader(f));
                            String line;
                            int lineCount = 0;
                            while ((line = fr.readLine()) != null && lineCount < 5000) {
                                res.append(line).append("\n");
                                lineCount++;
                            }
                            fr.close();
                            if (lineCount >= 5000) {
                                res.append("... (truncated at 5000 lines)\n");
                            }
                        }
                    } catch (Exception e) {
                        res.append("  ❌ Error: ").append(truncate(e.toString(), 200)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#STRINGS ")) {
                    String rest = firstLine.trim().substring(9).trim();
                    Log.i(TAG, "Command: STRINGS " + rest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== STRINGS SEARCH ===\n");
                    String[] parts = rest.split(" ", 2);
                    if (parts.length < 2) {
                        res.append("Usage: #STRINGS <filepath> <pattern>\n");
                    } else {
                        String filePath = parts[0];
                        String pattern = parts[1];
                        res.append("  File: ").append(filePath).append("\n");
                        res.append("  Pattern: ").append(pattern).append("\n");
                        try {
                            File f = new File(filePath);
                            if (!f.exists() || !f.canRead()) {
                                res.append("  ❌ Cannot read file\n");
                            } else {
                                Process process = Runtime.getRuntime().exec(
                                    new String[]{"sh", "-c", "strings '" + filePath + "' | grep -a '" + pattern + "' 2>/dev/null | sort -u | head -200"});
                                java.io.InputStream stdout = process.getInputStream();
                                java.util.Scanner scanner = new java.util.Scanner(stdout).useDelimiter("\\A");
                                String output = scanner.hasNext() ? scanner.next() : "";
                                int count = output.split("\n").length;
                                if (output.length() > 10000) output = output.substring(0, 10000) + "\n... (truncated)";
                                res.append(output);
                                process.waitFor();
                                res.append("Total: ").append(count).append(" lines\n");
                            }
                        } catch (Exception e) {
                            res.append("  ❌ Error: ").append(truncate(e.toString(), 200)).append("\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#GMSCAN")) {
                    Log.i(TAG, "Command: GMSCAN");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== GM ContentProvider SCAN ===\n");

                    String[] gmPkgs = {
                        "com.gm.hmi.settings",
                        "com.gm.hmi.connection",
                        "com.gm.hmianalytics",
                        "com.gm.hmi.onstarui",
                        "com.gm.hmi.onstar",
                        "com.gm.subscriptionmanager",
                        "com.gm.lcm",
                    };

                    for (String pkg : gmPkgs) {
                        sb.append("\n--- ").append(pkg).append(" ---\n");
                        try {
                            PackageInfo pi = getPackageManager().getPackageInfo(pkg,
                                PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA);

                            if (pi.providers != null && pi.providers.length > 0) {
                                for (ProviderInfo pri : pi.providers) {
                                    sb.append("  Provider: ").append(pri.name).append("\n");
                                    sb.append("    Authority: ").append(pri.authority).append("\n");
                                    sb.append("    Exported: ").append(pri.exported).append("\n");
                                    sb.append("    ReadPerm: ").append(pri.readPermission != null ? pri.readPermission : "(none)").append("\n");
                                    sb.append("    WritePerm: ").append(pri.writePermission != null ? pri.writePermission : "(none)").append("\n");

                                    if (pri.exported) {
                                        String auth = pri.authority;
                                        // Try base URI
                                        try {
                                            Uri uri = Uri.parse("content://" + auth + "/");
                                            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
                                            if (c != null) {
                                                sb.append("    QUERY /: ").append(c.getColumnCount()).append(" cols, ")
                                                  .append(c.getCount()).append(" rows\n");
                                                c.close();
                                            } else {
                                                sb.append("    QUERY /: null\n");
                                            }
                                        } catch (SecurityException e) {
                                            sb.append("    QUERY /: SECURITY ").append(truncate(e.toString(), 60)).append("\n");
                                        } catch (Exception e) {
                                            sb.append("    QUERY /: ").append(truncate(e.toString(), 60)).append("\n");
                                        }

                                        // Try settings sub-path
                                        try {
                                            Uri uri = Uri.parse("content://" + auth + "/settings");
                                            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
                                            if (c != null) {
                                                sb.append("    QUERY /settings: ").append(c.getColumnCount()).append(" cols, ")
                                                  .append(c.getCount()).append(" rows\n");
                                                c.close();
                                            } else {
                                                sb.append("    QUERY /settings: null\n");
                                            }
                                        } catch (SecurityException e) {
                                            sb.append("    QUERY /settings: SECURITY\n");
                                        } catch (Exception ignored) {}

                                        // Try CALL
                                        try {
                                            Uri uri2 = Uri.parse("content://" + auth);
                                            android.os.Bundle result = getContentResolver().call(uri2, "getSettings", null, null);
                                            sb.append("    CALL getSettings: ").append(result != null ? result.toString() : "null").append("\n");
                                        } catch (SecurityException e) {
                                            sb.append("    CALL getSettings: SECURITY ").append(truncate(e.toString(), 60)).append("\n");
                                        } catch (Exception e) {
                                            sb.append("    CALL getSettings: ").append(truncate(e.toString(), 60)).append("\n");
                                        }
                                    }
                                }
                            } else {
                                sb.append("  No ContentProviders found\n");
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            sb.append("  NOT INSTALLED\n");
                        } catch (Exception e) {
                            sb.append("  Error: ").append(truncate(e.toString(), 100)).append("\n");
                        }
                    }

                    netOut.write(sb.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#PROBE")) {
                    Log.i(TAG, "Command: PROBE");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== SYSTEM SERVICE PROBE ===\n");

                    String[][] services = {
                        {"screenprojection", "RDMSADBHandler (GM 投屏 ADB)"},
                        {"OBDService", "com.gm.server.obd (GM OBD)"},
                        {"GMAuthManagerService", "com.gm.android.gmauthmanagerservice (GM ADB认证)"},
                        {"engmode", "com.patac.hmi.engmode (工程模式)"},
                    };

                    try {
                        Class<?> smClass = Class.forName("android.os.ServiceManager");
                        java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);

                        for (String[] svcInfo : services) {
                            String svcName = svcInfo[0];
                            String desc = svcInfo[1];
                            sb.append("\n--- ").append(svcName).append(" ---\n");
                            sb.append("  Description: ").append(desc).append("\n");

                            try {
                                android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, svcName);
                                if (binder == null) {
                                    sb.append("  Service NOT FOUND on this system\n");
                                } else {
                                    sb.append("  Service FOUND!\n");
                                    sb.append("  Binder: ").append(binder).append("\n");
                                    sb.append("  Ping: ").append(binder.pingBinder()).append("\n");
                                    try {
                                        String iface = binder.getInterfaceDescriptor();
                                        sb.append("  Interface: ").append(iface).append("\n");
                                    } catch (Exception ignored) {}

                                    // Try transact code 1
                                    android.os.Parcel data = android.os.Parcel.obtain();
                                    android.os.Parcel reply = android.os.Parcel.obtain();
                                    try {
                                        String iface = binder.getInterfaceDescriptor();
                                        if (iface == null) iface = "android.os.IServiceManager";
                                        data.writeInterfaceToken(iface);
                                        boolean ok = binder.transact(1, data, reply, 0);
                                        sb.append("  transact(1): ").append(ok).append("\n");
                                        if (ok) {
                                            int avail = reply.dataAvail();
                                            sb.append("  Reply: ").append(avail).append(" bytes\n");
                                            if (avail > 0) {
                                                reply.setDataPosition(0);
                                                try {
                                                    byte[] raw = reply.marshall();
                                                    int limit = Math.min(raw.length, 64);
                                                    StringBuilder hex = new StringBuilder();
                                                    for (int i = 0; i < limit; i++) {
                                                        hex.append(String.format("%02x ", raw[i] & 0xFF));
                                                    }
                                                    sb.append("  HEX: ").append(hex).append("\n");
                                                } catch (RuntimeException e) {
                                                    try {
                                                        reply.setDataPosition(0);
                                                        String s = reply.readString();
                                                        sb.append("  Str: ").append(s).append("\n");
                                                    } catch (Exception e2) {
                                                        sb.append("  (unreadable reply data)\n");
                                                    }
                                                }
                                            }
                                        }
                                    } catch (SecurityException e) {
                                        sb.append("  transact SECURITY: ").append(truncate(e.toString(), 60)).append("\n");
                                    } catch (Exception e) {
                                        sb.append("  transact error: ").append(truncate(e.toString(), 60)).append("\n");
                                    } finally {
                                        data.recycle();
                                        reply.recycle();
                                    }

                                    // Try additional codes
                                    for (int code : new int[]{2, 3, 0x01, 0x1e}) {
                                        if (code == 1) continue;
                                        android.os.Parcel d2 = android.os.Parcel.obtain();
                                        android.os.Parcel r2 = android.os.Parcel.obtain();
                                        try {
                                            d2.writeInterfaceToken(binder.getInterfaceDescriptor());
                                            boolean ok = binder.transact(code, d2, r2, 0);
                                            if (ok && r2.dataAvail() > 0) {
                                                sb.append("  transact(").append(code).append("): OK, ").append(r2.dataAvail()).append(" bytes\n");
                                            }
                                        } catch (Exception ignored) {}
                                        finally { d2.recycle(); r2.recycle(); }
                                    }
                                }
                            } catch (Exception e) {
                                sb.append("  Error: ").append(truncate(e.toString(), 100)).append("\n");
                            }
                        }
                    } catch (Exception e) {
                        sb.append("  ServiceManager access error: ").append(truncate(e.toString(), 100)).append("\n");
                    }

                    netOut.write(sb.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#CLUSTER")) {
                    // v2.2: ShellBridge 原生 dashboard 投屏到仪表屏（数据源：HUD 广播 com.vox.hud.UPDATE）
                    Log.i(TAG, "Command: " + cmd);
                    handleCluster(cmd, client.getOutputStream());

                } else if (cmd.equals("#HELP")) {
                    Log.i(TAG, "Command: HELP");
                    OutputStream netOut = client.getOutputStream();
                    String help =
                        "ShellBridge v3.5 — Available commands:\n" +
                        "  #ACT <pkg> <cls> [--es k v] [--eu uri] [--ef file]  Launch activity with extras\n" +
                        "  #BIND <svc> [code] [--] [s:str] [i:int] [l:long] [b:byte]  Bind & transact(code) on system service\n" +
                        "  #CAR <svc> [code]  Get Android Automotive car service (IResultReceiver callback)\n" +
                        "  #UPDATE [path]  Install APK from /sdcard/Download/ (or custom path)\n" +
                        "  #VEHICLE    Show last known vehicle state (ignition, drive mode)\n" +
                        "  #ACTION <intent>  Launch by intent action\n" +
                        "  #NET      Network info (IP addresses, interfaces)\n" +
                        "  #QUERY <uri> [where] [args]  Query ContentProvider from Java context\n" +
                        "  #CALL <uri> <method> [arg]  Call ContentProvider method\n" +
                        "  #DUMPPKG <pkg>  Dump all components of a package\n" +
                        "  #SCANV4   New v4 scan (full settings, more providers, eng mode probe)\n" +
                        "  #SETTINGS Dump all Global/Secure/System settings\n" +
                        "  #ENUM     List third-party packages\n" +
                        "  #LAUNCH <pkg>  Launch app by package name\n" +
                        "  #ADB      Attempt ADB enablement\n" +
                        "  #ADB2     Enhanced ADB (SystemProperties, Binder, ctl.start, USB config)\n" +
                        "  #GMSCAN   Scan GM apps ContentProviders (WRITE_SECURE_SETTINGS)\n" +
                        "  #PROBE    Probe GM system services (screenprojection, OBD, engmode)\n" +
                        "  #GETDATA  Read /sdcard/Download/car_scan.txt\n" +
                        "  #NAV [code|pkg/cls code]  Bind & transact on com.sgm.navi.hmi MapService\n" +
                        "  #PERMS    Check runtime permission grant status\n" +
                        "  #GPS      Get GPS location (cached or fresh fix)\n" +
                        "  #MIC [secs]  Record microphone for N seconds (default 5)\n" +
                        "  #CAM      Test camera availability and take photo\n" +
                        "  #READ <path>  Read text file from filesystem\n" +
                        "  #STRINGS <path> <pattern>  Search strings in binary file\n" +
                        "  #CLUSTER LIST  List displays (dumpsys display parse, flags cluster/instrument candidates)\n" +
                        "  #CLUSTER START [displayId]  Launch ShellBridge native DashboardActivity on cluster display (am start --display)\n" +
                        "  #CLUSTER STOP  Close cluster dashboard (broadcast com.vox.shellbridge.FINISH_DASHBOARD)\n" +
                        "  #CLUSTER PROBE  Diagnostics for #CLUSTER (uid, su, sdk, dumpsys readability)\n" +
                        "  #HELP     This help\n" +
                        "  <cmd>     Run shell command\n";
                    netOut.write(help.getBytes());
                    netOut.flush();

                } else {
                    // 正常 shell 模式
                    Log.i(TAG, "Command: SHELL");
                    Process sh = Runtime.getRuntime().exec("sh");
                    OutputStream shStdin = sh.getOutputStream();
                    InputStream shStdout = sh.getInputStream();
                    InputStream shStderr = sh.getErrorStream();
                    OutputStream netOut = client.getOutputStream();

                    // 发送第一条命令
                    shStdin.write((firstLine + "\n").getBytes());
                    shStdin.flush();

                    // 后续网络数据 → sh stdin
                    Thread netToSh = new Thread(() -> {
                        try {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = netInRaw.read(buf)) != -1) {
                                shStdin.write(buf, 0, n);
                                shStdin.flush();
                            }
                        } catch (IOException ignored) {}
                    });
                    netToSh.setDaemon(true);
                    netToSh.start();

                    // sh stdout → net
                    Thread shToNet = new Thread(() -> {
                        try {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = shStdout.read(buf)) != -1) {
                                netOut.write(buf, 0, n);
                                netOut.flush();
                            }
                        } catch (IOException ignored) {}
                    });
                    shToNet.setDaemon(true);
                    shToNet.start();

                    // sh stderr → net
                    Thread errToNet = new Thread(() -> {
                        try {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = shStderr.read(buf)) != -1) {
                                netOut.write(buf, 0, n);
                                netOut.flush();
                            }
                        } catch (IOException ignored) {}
                    });
                    errToNet.setDaemon(true);
                    errToNet.start();

                    sh.waitFor();
                    Log.i(TAG, "Shell exited for " + client.getInetAddress().getHostAddress());
                }
            } catch (Exception e) {
                Log.e(TAG, "Client handler error", e);
                // Send error response back to client instead of silent crash
                try {
                    OutputStream netOut = client.getOutputStream();
                    String errMsg = "ERROR: " + e.toString() + "\n";
                    try {
                        java.io.StringWriter sw = new java.io.StringWriter();
                        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                        e.printStackTrace(pw);
                        String trace = sw.toString();
                        errMsg += "TRACE: " + trace.substring(0, Math.min(trace.length(), 500)) + "\n";
                    } catch (Exception ignored) {}
                    netOut.write(errMsg.getBytes());
                    netOut.flush();
                } catch (IOException ignored) {}
            } finally {
                try { client.close(); } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ===== #CLUSTER (v2.2): ShellBridge 原生 dashboard 投屏（数据源：HUD 广播 com.vox.hud.UPDATE）=====

    /** Run a command via sh -c with stderr merged into stdout; returns full output (capped 512KB). */
    private static String runCapture(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command + " 2>&1"});
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > 512 * 1024) break;
            }
            p.waitFor();
            return new String(bos.toByteArray());
        } catch (Exception e) {
            return "(exec error: " + e + ")";
        }
    }

    /** Parse `dumpsys display` output into rows of [displayId, name, type, resolution]. */
    private static java.util.List<String[]> parseDisplays(String dump) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        // id -> "WxH" map from "Display{#N state=ON size=1920x720 ...}" lines
        java.util.Map<String, String> sizeById = new java.util.HashMap<>();
        java.util.regex.Matcher mb = java.util.regex.Pattern.compile(
                "Display\\{#(\\d+)[^\\n]*?size=(\\d+x\\d+)").matcher(dump);
        while (mb.find()) sizeById.put(mb.group(1), mb.group(2));

        java.util.regex.Pattern pName = java.util.regex.Pattern.compile(
                "DisplayDeviceInfo\\{\"([^\"]*)\"");
        java.util.regex.Pattern pIdAttr = java.util.regex.Pattern.compile("displayId=(\\d+)");
        java.util.regex.Pattern pUid = java.util.regex.Pattern.compile(
                "uniqueDisplayId=\"[^\"]*:(\\d+)\"");
        java.util.regex.Pattern pUidAny = java.util.regex.Pattern.compile(
                ":(\\d+)\"");
        java.util.regex.Pattern pType = java.util.regex.Pattern.compile("\\btype=([A-Za-z_0-9]+)");
        java.util.regex.Pattern pWH = java.util.regex.Pattern.compile(
                "(?:physicalWidth|deviceWidth|logicalWidth|activeWidth)[=:](\\d+)[^\\n]*?" +
                "(?:physicalHeight|deviceHeight|logicalHeight|activeHeight)[=:](\\d+)");

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String line : dump.split("\n")) {
            if (line.indexOf("DisplayDeviceInfo") < 0) continue;
            java.util.regex.Matcher mn = pName.matcher(line);
            String name = mn.find() ? mn.group(1) : "(unnamed)";
            String id = null;
            java.util.regex.Matcher mi = pIdAttr.matcher(line);
            if (mi.find()) id = mi.group(1);
            if (id == null) { mi = pUid.matcher(line); if (mi.find()) id = mi.group(1); }
            if (id == null) { mi = pUidAny.matcher(line); if (mi.find()) id = mi.group(1); }
            if (id == null) id = "?";
            if (!seen.add(id + "|" + name)) continue;
            java.util.regex.Matcher mt = pType.matcher(line);
            String type = mt.find() ? mt.group(1) : "?";
            java.util.regex.Matcher mw = pWH.matcher(line);
            String res = mw.find() ? mw.group(1) + "x" + mw.group(2)
                    : (sizeById.containsKey(id) ? sizeById.get(id) : "?");
            rows.add(new String[]{id, name, type, res});
        }
        return rows;
    }

    /** Indent every line of a block by two spaces (for nested output). */
    private static String indentBlock(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) sb.append("  ").append(line).append("\n");
        return sb.toString().trim();
    }

    /** #CLUSTER LIST | START [displayId] | STOP | PROBE — cmd is already uppercased. */
    private void handleCluster(String cmd, OutputStream netOut) throws IOException {
        String[] parts = cmd.split("\\s+");
        String sub = parts.length > 1 ? parts[1] : "";
        StringBuilder res = new StringBuilder();

        if (sub.equals("LIST")) {
            res.append("=== CLUSTER LIST ===\n");
            String dump = runCapture("dumpsys display");
            java.util.List<String[]> rows = parseDisplays(dump);
            if (rows.isEmpty()) {
                res.append("  (no DisplayDeviceInfo lines parsed; raw head:)\n");
                res.append(indentBlock(truncate(dump, 800))).append("\n");
            }
            for (String[] r : rows) {
                String tag = "";
                String hay = (r[1] + " " + r[2]).toLowerCase();
                if (r[0].equals("0")) tag = " [main]";
                else if (hay.contains("cluster") || hay.contains("instrument") || hay.contains("secondary"))
                    tag = " [cluster?]";
                res.append("  id=").append(r[0])
                   .append("  name=\"").append(r[1]).append("\"")
                   .append("  type=").append(r[2])
                   .append("  res=").append(r[3])
                   .append(tag).append("\n");
            }

        } else if (sub.equals("START")) {
            res.append("=== CLUSTER START ===\n");
            String id = parts.length > 2 ? parts[2] : "";
            if (id.isEmpty()) {
                // Auto-pick: first non-zero display matching cluster|instrument|secondary
                java.util.List<String[]> rows = parseDisplays(runCapture("dumpsys display"));
                String picked = null;
                for (String[] r : rows) {
                    if (r[0].equals("0")) continue;
                    String hay = (r[1] + " " + r[2]).toLowerCase();
                    if (hay.contains("cluster") || hay.contains("instrument") || hay.contains("secondary")) {
                        picked = r[0];
                        break;
                    }
                }
                if (picked == null) {
                    res.append("  No cluster-like display auto-detected (name/type matches cluster|instrument|secondary, excluding id 0).\n");
                    res.append("  Run #CLUSTER LIST and pass an explicit id: #CLUSTER START <displayId>\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();
                    return;
                }
                res.append("  Auto-picked displayId=").append(picked).append("\n");
                id = picked;
            }
            String amCmd = "am start --display " + id
                    + " -f 0x10000000 -n com.vox.shellbridge/.DashboardActivity";
            res.append("  CMD: ").append(amCmd).append("\n");
            String out = runCapture(amCmd);
            String mode = "direct";
            String low = out.toLowerCase();
            if ((low.contains("securityexception") || low.contains("permission denial")
                    || low.contains("permissiondenied"))
                    && !runCapture("which su").trim().isEmpty()) {
                res.append("  direct failed (permission denial), retrying via su -c ...\n");
                String suOut = runCapture("su -c '" + amCmd + "'");
                if (!suOut.toLowerCase().contains("not found")) {
                    mode = "su";
                    out = suOut;
                }
            }
            res.append("  Mode: ").append(mode).append("\n");
            res.append(indentBlock(truncate(out, 2000))).append("\n");

        } else if (sub.equals("STOP")) {
            res.append("=== CLUSTER STOP ===\n");
            String out = runCapture("am broadcast -a com.vox.shellbridge.FINISH_DASHBOARD");
            res.append(indentBlock(truncate(out, 1000))).append("\n");

        } else if (sub.equals("PROBE")) {
            res.append("=== CLUSTER PROBE ===\n");
            res.append("  id: ").append(truncate(runCapture("id").trim(), 200)).append("\n");
            String su = runCapture("which su").trim();
            res.append("  which su: ").append(su.isEmpty() ? "(none)" : truncate(su, 200)).append("\n");
            res.append("  ro.build.version.sdk: ")
               .append(truncate(runCapture("getprop ro.build.version.sdk").trim(), 50)).append("\n");
            String dump = runCapture("dumpsys display");
            boolean readable = !dump.startsWith("(exec error")
                    && !dump.toLowerCase().contains("permission denial");
            res.append("  dumpsys display: ").append(readable
                    ? "readable (" + dump.length() + " bytes)"
                    : "NOT readable: " + truncate(dump, 200)).append("\n");

        } else {
            res.append("Usage: #CLUSTER LIST | #CLUSTER START [displayId] | #CLUSTER STOP | #CLUSTER PROBE\n");
        }
        netOut.write(res.toString().getBytes());
        netOut.flush();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
