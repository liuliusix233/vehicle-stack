package com.vox.castsink;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.util.List;

/**
 * CarScanner v5 — proper Intent-based activity launch, ActivityLauncher integration.
 */
public class CarScanner {
    private static final String TAG = "CarScanner";
    private static final String OUT_FILE = "/sdcard/Download/car_scan.txt";
    private final ContentResolver resolver;
    private final Context context;
    private final StringBuilder sb = new StringBuilder();

    public interface ScanCallback {
        void onResult(String result);
    }
    private ScanCallback callback;

    public CarScanner(ContentResolver resolver, Context context) {
        this.resolver = resolver;
        this.context = context;
    }

    public void setCallback(ScanCallback cb) { this.callback = cb; }

    public void scan() {
        println("=== CarScanner v5 ===");
        println("Time: " + System.currentTimeMillis());
        println("Package: " + context.getPackageName());
        println("UID: " + android.os.Process.myUid());
        println("");

        readSettingsFromJava();
        println("");
        tryEnableAdb();
        println("");
        scanProviders();
        println("");
        trySystemPropertiesReflection();
        println("");
        probeEngineeringMode();
        println("");
        tryLaunchApps();
        println("");
        tryContentProviderCalls();
        println("");
        trySettingsIntents();
        println("");
        dumpAllSettings();

        String result = sb.toString();
        writeOutput(result);
        if (callback != null) callback.onResult(result);
        Log.i(TAG, "Scan complete (" + result.length() + " chars)");
    }

    public String dumpSettings() {
        dumpAllSettings();
        String result = sb.toString();
        if (callback != null) callback.onResult(result);
        return result;
    }

    // ====== Low-level helpers ======

    private void println(String s) { sb.append(s).append("\n"); }

    private void queryAndDump(String label, Uri uri, int maxRows) {
        println("  [" + label + "] " + uri);
        try {
            Cursor cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null) {
                println("    \u2192 " + cursor.getCount() + " rows");
                String[] cols = cursor.getColumnNames();
                if (cols != null) {
                    StringBuilder colLine = new StringBuilder("      cols: ");
                    for (String c : cols) colLine.append(c).append(", ");
                    println(colLine.toString());
                }
                int n = 0;
                while (cursor.moveToNext() && n < maxRows) {
                    StringBuilder row = new StringBuilder("      row #" + n + ": ");
                    if (cols != null) {
                        for (String col : cols) {
                            int idx = cursor.getColumnIndex(col);
                            if (idx >= 0) {
                                String val = cursor.getString(idx);
                                if (val != null && val.length() > 80) val = val.substring(0, 80) + "...";
                                row.append(col).append("=").append(val != null ? val : "null").append(" ");
                            }
                        }
                    }
                    println(row.toString());
                    n++;
                }
                if (cursor.getCount() > n) {
                    println("      ... (" + (cursor.getCount() - n) + " more rows)");
                }
                cursor.close();
            } else {
                println("    \u2192 null cursor");
            }
        } catch (SecurityException e) {
            println("    \u2192 SecurityException: " + truncate(e.getMessage(), 100));
        } catch (Exception e) {
            println("    \u2192 Error: " + truncate(e.toString(), 100));
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ====== Settings from Java ======

    private void readSettingsFromJava() {
        println("=== SETTINGS READ (from Java) ===");
        String[][] keys = {
            {"global", "development_settings_enabled"},
            {"global", "adb_enabled"},
            {"global", "wifi_on"},
            {"global", "install_non_market_apps"},
            {"system", "adb_port"},
            {"secure", "adb_enabled"},
            {"global", "wifi_on"},
            {"global", "airplane_mode_on"},
            {"global", "bluetooth_on"},
            {"global", "stay_on_while_plugged_in"},
            {"global", "wifi_sleep_policy"},
            {"global", "mobile_data"},
            {"global", "data_roaming"},
            {"global", "device_name"},
            {"global", "usb_mass_storage_enabled"},
            {"global", "boot_count"},
            {"system", "volume_music"},
            {"system", "volume_ring"},
            {"system", "volume_system"},
            {"system", "screen_brightness"},
            {"system", "screen_off_timeout"},
            {"system", "screen_brightness_mode"},
        };
        for (String[] k : keys) {
            String table = k[0];
            String key = k[1];
            try {
                String val = null;
                switch (table) {
                    case "global": val = Settings.Global.getString(resolver, key); break;
                    case "system": val = Settings.System.getString(resolver, key); break;
                    case "secure": val = Settings.Secure.getString(resolver, key); break;
                }
                println("  " + table + "." + key + " = " + (val != null ? val : "(null)"));
            } catch (Exception e) {
                println("  " + table + "." + key + " = (blocked) " + truncate(e.toString(), 60));
            }
        }
    }

    // ====== ADB attempts ======

    private void tryEnableAdb() {
        println("=== ADB ENABLEMENT ATTEMPTS ===");
        try {
            println("  Attempt 1: Settings.Global.putInt(adb_enabled=1)...");
            boolean ok = Settings.Global.putInt(resolver, "adb_enabled", 1);
            println("    result=" + ok);
        } catch (Exception e) {
            println("    failed: " + truncate(e.toString(), 80));
        }
        try {
            println("  Attempt 2: Settings.System.putInt...");
            boolean ok = Settings.System.putInt(resolver, "adb_enabled", 1);
            println("    result=" + ok);
        } catch (Exception e) {
            println("    failed: " + truncate(e.toString(), 80));
        }
        try {
            println("  Attempt 3: enable development_settings...");
            boolean ok = Settings.Global.putInt(resolver, "development_settings_enabled", 1);
            println("    result=" + ok);
        } catch (Exception e) {
            println("    failed: " + truncate(e.toString(), 80));
        }
        println("  Attempt 4: Runtime.exec(adb start / setprop)...");
        String[][] execCmds = {
            {"setprop persist.sys.usb.config diag,mtp,adb", "setprop"},
            {"start adbd", "start adbd"},
            {"setprop service.adb.tcp.port 5555", "setprop tcp.port"},
        };
        for (String[] ec : execCmds) {
            String cmd = ec[0];
            String name = ec[1];
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                int ret = p.waitFor();
                println("    " + name + " exit=" + ret);
            } catch (Exception e) {
                println("    " + name + " failed: " + truncate(e.toString(), 60));
            }
        }
        println("  Attempt 5: Runtime.exec(su)...");
        String[] suCmds = {
            "setprop persist.sys.usb.config diag,mtp,adb",
            "start adbd",
            "settings put global adb_enabled 1",
        };
        for (String cmd : suCmds) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                StringBuilder err = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) err.append(line);
                int ret = p.waitFor();
                println("    su -c '" + cmd + "' exit=" + ret + " err=" + err);
            } catch (Exception e2) {
                println("    su failed: " + truncate(e2.toString(), 60));
            }
        }
    }

    // ====== ContentProvider scan ======

    private void scanProviders() {
        println("=== CONTENT PROVIDER SCAN ===");
        String[][] providers = {
            {"content://com.google.settings/partner", "Google partner"},
            {"content://settings/global", "Settings Global"},
            {"content://settings/secure", "Settings Secure"},
            {"content://settings/system", "Settings System"},
            {"content://com.android.car/vehicle", "Android Auto Vehicle"},
            {"content://com.patac.hmi.provider.settings", "Patac HMI settings"},
            {"content://com.gm.ddb_contentprovider", "GM DDB"},
            {"content://com.gm.favoritesprovider", "GM Favorites"},
            {"content://com.gm.authtoken", "GM AuthToken"},
            {"content://com.gm.hmi.onstar", "GM OnStar HMI"},
            {"content://com.gm.ultifi.gmconnectedcamerahmi", "GM Connected Camera"},
            {"content://com.gm.ultifi.gmwirelesscamera", "GM Wireless Camera"},
            {"content://com.patac.hmi.settings", "Patac Settings"},
            {"content://com.patac.pki", "Patac PKI"},
            {"content://com.patac.datatrack", "Patac DataTrack"},
            {"content://com.gm.permissionproxy", "GM PermissionProxy"},
            {"content://com.gm.hmi.gallery", "GM Gallery"},
            {"content://com.gm.hmi.themes", "GM Themes"},
            {"content://com.gm.hmi.driving", "GM Driving data"},
            {"content://com.gm.hmi.vehicledata", "GM Vehicle data"},
            {"content://com.gm.hmi.settings", "GM Settings"},
            {"content://com.gm.hmi.navigation", "GM Navigation"},
            {"content://com.gm.hmi.voice", "GM Voice"},
            {"content://com.gm.hmi.bluetooth", "GM Bluetooth"},
            {"content://android.car.vehicle", "Car Vehicle"},
            {"content://com.patac.hmi.vehicle.info", "Patac Vehicle info"},
            {"content://com.patac.hmi.obd.provider", "Patac OBD"},
            {"content://com.gm.server.obd", "GM OBD Server"},
            {"content://com.patac.hmi.obd.provider/obd", "Patac OBD /obd"},
            {"content://com.patac.hmi.obd.provider/vehicle", "Patac OBD /vehicle"},
            {"content://com.patac.hmi.obd.provider/data", "Patac OBD /data"},
            {"content://com.gm.server.obd/obd", "GM OBD /obd"},
            {"content://com.gm.server.obd/vehicle", "GM OBD /vehicle"},
            {"content://com.gm.hmi.vehicledata/data", "GM Vehicle data /data"},
            {"content://com.gm.hmi.vehicledata/vehicle", "GM Vehicle data /vehicle"},
            {"content://com.gm.hmi.settings/settings", "GM Settings /settings"},
            {"content://com.patac.hmi.settings/settings", "Patac Settings /settings"},
            {"content://com.gm.authtoken/token", "GM AuthToken /token"},
            {"content://com.gm.hmi.onstar/status", "GM OnStar /status"},
            {"content://com.gm.hmi.onstar/vehicle", "GM OnStar /vehicle"},
            {"content://com.patac.hmi.vehicle.info/info", "Patac Vehicle info /info"},
            {"content://com.patac.hmi.vehicle.info/vin", "Patac Vehicle info /vin"},
            {"content://com.patac.hmi.vehicle.info/status", "Patac Vehicle info /status"},
            {"content://com.gm.authtoken/token/vehicle", "GM AuthToken /token/vehicle"},
            {"content://com.gm.authtoken/onstar", "GM AuthToken /onstar"},
            {"content://com.gm.hmi.onstar/status/vehicle", "GM OnStar /status/vehicle"},
            {"content://com.patac.hmi.vehicle.info/data", "Patac Vehicle info /data"},
        };
        for (String[] p : providers) {
            queryAndDump(p[1], Uri.parse(p[0]), 5);
        }
    }

    // ====== SystemProperties via reflection ======

    private void trySystemPropertiesReflection() {
        println("=== SYSTEM PROPERTIES (reflection) ===");
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            String[] keys = {
                "persist.sys.usb.config", "service.adb.tcp.port",
                "init.svc.adbd", "persist.adb.tcp.port",
                "ro.adb.secure", "ro.debuggable", "ro.secure",
                "sys.usb.state", "ro.serialno", "ro.boot.serialno",
                "ro.product.vendor.device", "persist.sys.gm.build_id",
                "ro.build.fingerprint", "ro.board.platform",
                "init.svc.gm_vehicle_hal", "init.svc.bosch-diagnostics-service",
                "init.svc.RDMS", "init.svc.diagnosticsd",
                "net.hostname",
            };
            for (String key : keys) {
                String val = (String) get.invoke(null, key, "(unavailable)");
                println("  " + key + " = " + val);
            }
        } catch (Exception e) {
            println("  Reflection failed: " + e);
        }
    }

    // ====== Engineering mode & App launcher ======

    private void probeEngineeringMode() {
        println("=== ENGINEERING MODE PROBE ===");
        String[][] appInfos = {
            {"com.patac.hmi.engmode", "Patac Engineering Mode"},
            {"com.patac.hmi.dba", "Patac DBA Diagnostics"},
            {"com.patac.hmi.svr", "Patac SVR"},
            {"com.android.car.developeroptions", "Car Developer Options"},
            {"de.szalkowski.activitylauncher", "Activity Launcher"},
            {"com.patac.datatrack", "Patac DataTrack"},
            {"com.gm.authtoken", "GM AuthToken"},
            {"com.patac.pki", "Patac PKI"},
        };
        PackageManager pm = context.getPackageManager();
        for (String[] info : appInfos) {
            String pkg = info[0];
            String name = info[1];
            try {
                pm.getPackageInfo(pkg, 0);
                println("  [" + name + "] " + pkg + " INSTALLED");
                
                // Try launch intent
                Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                if (launchIntent != null) {
                    println("    \u2192 launcher intent: " + launchIntent);
                    println("    \u2192 component: " + launchIntent.getComponent());
                    // Actually try to start it!
                    try {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                        println("    \u2192 STARTED SUCCESSFULLY!");
                    } catch (Exception e2) {
                        println("    \u2192 start failed: " + truncate(e2.toString(), 100));
                    }
                } else {
                    println("    \u2192 no launcher intent, scanning exported activities...");
                    // Find any exported activity
                    Intent query = new Intent(Intent.ACTION_MAIN);
                    query.setPackage(pkg);
                    List<ResolveInfo> acts = pm.queryIntentActivities(query, 0);
                    if (acts != null && !acts.isEmpty()) {
                        for (ResolveInfo ri : acts) {
                            println("    \u2192 activity: " + ri.activityInfo.name
                                + " exported=" + ri.activityInfo.exported);
                            // Try to start each one
                            if (ri.activityInfo.exported) {
                                try {
                                    Intent aIntent = new Intent();
                                    aIntent.setClassName(pkg, ri.activityInfo.name);
                                    aIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    context.startActivity(aIntent);
                                    println("      \u2192 STARTED!");
                                } catch (Exception e3) {
                                    println("      \u2192 failed: " + truncate(e3.toString(), 80));
                                }
                            }
                        }
                    } else {
                        println("    \u2192 no exported main activities");
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                println("  [" + name + "] " + pkg + " NOT INSTALLED");
            }
        }
    }

    // ====== Try launching key apps ======

    private void tryLaunchApps() {
        println("=== KEY APP LAUNCH ATTEMPTS ===");
        String[][] appsToTry = {
            {"com.tailscale.ipn", ".IPN", "Tailscale"},
            {"io.homeassistant.companion.android.minimal", "", "HA Minimal"},
            {"com.github.kr328.clash", "", "Clash"},
            {"com.microsoft.emmx", "", "Edge"},
            {"com.limelight", "", "Moonlight"},
        };
        PackageManager pm = context.getPackageManager();
        for (String[] app : appsToTry) {
            String pkg = app[0];
            String className = app[1];
            String label = app[2];
            try {
                pm.getPackageInfo(pkg, 0);
                println("  [" + label + "] " + pkg + " installed");
                Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                if (launchIntent != null) {
                    println("    \u2192 launcher: " + launchIntent.getComponent());
                } else {
                    println("    \u2192 no launcher intent");
                }
            } catch (PackageManager.NameNotFoundException e) {
                println("  [" + label + "] " + pkg + " not installed");
            }
        }
    }

    // ====== ContentProvider call() attempts ======

    private void tryContentProviderCalls() {
        println("=== CONTENT PROVIDER call() ATTEMPTS ===");
        // Try calling custom methods on providers
        String[][] calls = {
            {"content://com.gm.server.obd", "getVehicleData"},
            {"content://com.gm.server.obd", "getOBDData"},
            {"content://com.gm.server.obd", "getStatus"},
            {"content://com.gm.server.obd/vehicle", "query"},
            {"content://com.gm.server.obd/obd", "query"},
            {"content://com.patac.hmi.obd.provider/vehicle", "getData"},
            {"content://com.patac.hmi.vehicle.info", "getVehicleInfo"},
            {"content://com.gm.hmi.vehicledata", "getVehicleData"},
            {"content://com.gm.hmi.onstar", "getVehicleStatus"},
            {"content://com.gm.authtoken", "getToken"},
            {"content://com.gm.authtoken", "getAuthToken"},
            {"content://com.gm.hmi.onstar/status", "query"},
            {"content://com.patac.hmi.vehicle.info", "getVIN"},
            {"content://com.patac.hmi.vehicle.info", "getStatus"},
            {"content://com.android.car/vehicle", "getProperty"},
            {"content://com.android.car/vehicle", "getVendorProperty"},
            {"content://android.car.vehicle", "getVehicleData"},
            {"content://com.patac.datatrack", "getData"},
            {"content://com.gm.ddb_contentprovider", "getData"},
            {"content://com.patac.service", "getVehicleStatus"},
        };
        for (String[] call : calls) {
            String uriStr = call[0];
            String method = call[1];
            try {
                Uri uri = Uri.parse(uriStr);
                Bundle result = resolver.call(uri, method, null, null);
                if (result != null) {
                    println("  [" + method + "] on " + uriStr);
                    println("    \u2192 result size=" + result.size());
                    for (String key : result.keySet()) {
                        Object val = result.get(key);
                        String valStr = val != null ? val.toString() : "null";
                        if (valStr.length() > 100) valStr = valStr.substring(0, 100) + "...";
                        println("      " + key + " = " + valStr);
                    }
                } else {
                    // null result might mean method not found
                }
            } catch (SecurityException e) {
                // silently ignore - expected for most providers
            } catch (Exception e) {
                // silently ignore - method not supported
            }
        }
        // Try with empty bundle to see if any methods return results
        String[] probeURIs = {
            "content://com.gm.server.obd",
            "content://com.patac.hmi.obd.provider",
            "content://com.gm.hmi.vehicledata",
            "content://com.gm.hmi.onstar",
            "content://com.gm.authtoken",
            "content://com.patac.hmi.vehicle.info",
            "content://com.patac.datatrack",
            "content://com.gm.ddb_contentprovider",
            "content://com.android.car/vehicle",
            "content://android.car.vehicle",
        };
        for (String uriStr : probeURIs) {
            // Try with null method ("" or null)
            try {
                Uri uri = Uri.parse(uriStr);
                Bundle result = resolver.call(uri, "", null, null);
                if (result != null) {
                    println("  [empty method] on " + uriStr + " -> " + result.size() + " keys");
                }
            } catch (Exception ignored) {}
            // Try query with null projection
            try {
                Uri uri = Uri.parse(uriStr);
                Cursor c = resolver.query(uri, null, null, null, null);
                if (c != null) {
                    println("  [query] on " + uriStr + " -> " + c.getCount() + " rows");
                    c.close();
                }
            } catch (Exception ignored) {}
        }
        // Done - report what we found
        println("  (call() results are only shown for non-null responses)");
    }

    // ====== Settings shortcut intents ======

    private void trySettingsIntents() {
        println("=== SETTINGS INTENTS ===" );
        Intent[] intents = new Intent[] {
            new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS),
            new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
            new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            new Intent("com.android.car.settings.DEVELOPMENT"),
            new Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS),
            new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
        };
        for (Intent intent : intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                println("  STARTED: " + intent.getAction());
            } catch (Exception e) {
                // silently ignore failures
            }
        }
        println("  (attempted " + intents.length + " intents)");
    }

    // ====== Full settings dump ======

    private void dumpAllSettings() {
        println("=== FULL SETTINGS DUMP ===");
        println("--- Settings.Global ---");
        dumpTable(Uri.parse("content://settings/global"));
        println("--- Settings.Secure ---");
        dumpTable(Uri.parse("content://settings/secure"));
        println("--- Settings.System ---");
        dumpTable(Uri.parse("content://settings/system"));
    }

    private void dumpTable(Uri uri) {
        try {
            Cursor cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null) {
                println("  (" + cursor.getCount() + " rows)");
                String[] cols = cursor.getColumnNames();
                int nameIdx = -1, valIdx = -1;
                if (cols != null) {
                    for (int i = 0; i < cols.length; i++) {
                        if ("name".equals(cols[i])) nameIdx = i;
                        if ("value".equals(cols[i])) valIdx = i;
                    }
                }
                while (cursor.moveToNext()) {
                    String name = nameIdx >= 0 ? cursor.getString(nameIdx) : "?";
                    String value = valIdx >= 0 ? cursor.getString(valIdx) : "?";
                    if (value != null && value.length() > 100) value = value.substring(0, 100) + "...";
                    println("    " + name + " = " + (value != null ? value : "(null)"));
                }
                cursor.close();
            }
        } catch (Exception e) {
            println("  (error: " + truncate(e.toString(), 80) + ")");
        }
    }

    private void writeOutput(String data) {
        try {
            File outFile = new File(OUT_FILE);
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(outFile));
            writer.write(data);
            writer.close();
            outFile.setReadable(true, false);
            Log.i(TAG, "Written to " + OUT_FILE + " (" + data.length() + " bytes)");
        } catch (Exception e) {
            Log.w(TAG, "Cannot write to " + OUT_FILE + ": " + e);
        }
    }
}
