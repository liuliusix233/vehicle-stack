package com.vox.castsink;

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
    private static final StringBuilder DCS_LOG = new StringBuilder();
    private static IBinder DCS_LISTENER = null;
    private static IBinder DCS_BINDER = null;
    private static IBinder BRIDGE_BINDER = null;
    private static ServiceConnection DCS_CONN = null;
    private static final int PORT = 8888;
    private static final int NOTIFICATION_ID = 2;

    // Vehicle state (updated by VehicleReceiver)
    public static volatile int lastIgnitionState = -1;     // -1=unknown, 0=OFF, 1=ON
    public static volatile int lastDriveMode = -1;          // -1=unknown, 0=P, 1=R, 2=N, 3=D
    public static volatile long lastVehicleUpdate = 0;

    private ServerSocket serverSocket;
    private android.media.MediaPlayer audioPlayer;
    private ExecutorService threadPool;
    private volatile boolean running = true;

    // v130: #BINDSVC binder 缓存 — 按 pkg+cls 复用连接，空闲 30s 后由后台线程 unbind
    private static final java.util.Map<String, BinderCacheEntry> BINDSVC_CACHE = new java.util.HashMap<>();
    private static final class BinderCacheEntry {
        final IBinder binder; final ServiceConnection conn; long lastUsed;
        BinderCacheEntry(IBinder b, ServiceConnection c) { binder = b; conn = c; lastUsed = System.currentTimeMillis(); }
    }
    private Thread binderSweeper;

    @Override
    public void onCreate() {
        super.onCreate();
        try { CarBus.INSTANCE.start(); } catch (Throwable t) { Log.w(TAG, "CarBus start failed: " + t); }
        try { NavPush.INSTANCE.start(); } catch (Throwable t) { Log.w(TAG, "NavPush start failed: " + t); }
        try { RouteCb.INSTANCE.start(this); } catch (Throwable t) { Log.w(TAG, "RouteCb start failed: " + t); }
        try { RelayTunnel.INSTANCE.start(getApplicationContext()); } catch (Throwable ignored) {}
        threadPool = Executors.newCachedThreadPool();
        startBinderCacheSweeper();

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
                        .setContentTitle("CarBridge · Shell + Cast")
                        .setContentText("端口 " + PORT + " 已就绪")
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
                        try {
                            serverSocket = new ServerSocket(PORT);
                            Log.i(TAG, "Listening on 0.0.0.0:" + PORT);
                        } catch (IOException bindErr) {
                            Log.w(TAG, "Bind 8888 failed: " + bindErr.getMessage());
                            tryTakeoverLegacyBridge();
                            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                            continue;
                        }
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

    // ===== v130: BINDSVC binder 缓存与 transact 辅助（从原内联逻辑抽出，输出格式与 v129 一致）=====

    private void startBinderCacheSweeper() {
        if (binderSweeper != null) return;
        binderSweeper = new Thread(() -> {
            while (running) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                try {
                    long now = System.currentTimeMillis();
                    java.util.List<ServiceConnection> victims = new java.util.ArrayList<>();
                    synchronized (BINDSVC_CACHE) {
                        java.util.Iterator<java.util.Map.Entry<String, BinderCacheEntry>> it =
                                BINDSVC_CACHE.entrySet().iterator();
                        while (it.hasNext()) {
                            java.util.Map.Entry<String, BinderCacheEntry> en = it.next();
                            if (now - en.getValue().lastUsed > 30000L) { victims.add(en.getValue().conn); it.remove(); }
                        }
                    }
                    for (ServiceConnection c : victims) { try { unbindService(c); } catch (Exception ignored) {} }
                } catch (Throwable t) {
                    Log.w(TAG, "binder sweeper: " + t);
                }
            }
        }, "bindsvc-sweeper");
        binderSweeper.setDaemon(true);
        binderSweeper.start();
    }

    /** v130: 清空缓存并全部 unbind（服务销毁/进程退出） */
    private void clearBinderCache() {
        java.util.List<ServiceConnection> all = new java.util.ArrayList<>();
        synchronized (BINDSVC_CACHE) {
            for (java.util.Map.Entry<String, BinderCacheEntry> en : BINDSVC_CACHE.entrySet()) all.add(en.getValue().conn);
            BINDSVC_CACHE.clear();
        }
        for (ServiceConnection c : all) { try { unbindService(c); } catch (Exception ignored) {} }
    }

    /** v130: #BINDSVC 共用路径 — 缓存命中直接 transact；未命中/异常回退原 bind→transact（结果入缓存，30s 空闲后 unbind） */
    private void bindsvcShared(String pkg, String cls, String[] mainArgs, String chainPart, StringBuilder res) throws Exception {
        String key = pkg + "/" + cls;
        BinderCacheEntry hit = null;
        synchronized (BINDSVC_CACHE) {
            BinderCacheEntry e = BINDSVC_CACHE.get(key);
            if (e != null && e.binder != null) {
                boolean alive = true;
                try { alive = e.binder.pingBinder(); } catch (Exception ignored) {}
                if (alive) { e.lastUsed = System.currentTimeMillis(); hit = e; }
                else BINDSVC_CACHE.remove(key);
            }
        }
        if (hit != null) {
            try {
                res.append("Connected: ").append(hit.binder).append("\n");
                res.append("Iface: ").append(hit.binder.getInterfaceDescriptor()).append("\n");
                res.append("Ping: ").append(hit.binder.pingBinder()).append("\n");
                transactOnBinder(hit.binder, mainArgs, chainPart, res);
                return;
            } catch (Exception e) {
                res.append("cache err: ").append(truncate(e.toString(), 80)).append(" -> drop cache, rebind\n");
                synchronized (BINDSVC_CACHE) { if (BINDSVC_CACHE.get(key) == hit) BINDSVC_CACHE.remove(key); }
                try { unbindService(hit.conn); } catch (Exception ignored) {}
            }
        }
        // 缓存未命中/失效：原「bind→transact→unbind」路径（v130：结果入缓存供复用，不再立即 unbind）
        final CountDownLatch latch = new CountDownLatch(1);
        final IBinder[] boundBinder = new IBinder[1];
        final String[] errorMsg = new String[1];
        ServiceConnection sc = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                boundBinder[0] = service; latch.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                errorMsg[0] = "disconnected"; latch.countDown();
            }
        };
        Intent intent = new Intent();
        intent.setClassName(pkg, cls);
        boolean bound = bindService(intent, sc, ShellService.this.BIND_AUTO_CREATE);
        if (!bound) { res.append("bindService returned false\n"); return; }
        boolean timedOut = !latch.await(5000, TimeUnit.MILLISECONDS);
        if (timedOut) {
            res.append("Timed out (5s)\n");
            try { unbindService(sc); } catch (Exception ignored) {}
            return;
        }
        if (boundBinder[0] == null) {
            res.append("Got null binder\n");
            try { unbindService(sc); } catch (Exception ignored) {}
            return;
        }
        IBinder svc = boundBinder[0];
        res.append("Connected: ").append(svc).append("\n");
        res.append("Iface: ").append(svc.getInterfaceDescriptor()).append("\n");
        res.append("Ping: ").append(svc.pingBinder()).append("\n");
        transactOnBinder(svc, mainArgs, chainPart, res);
        synchronized (BINDSVC_CACHE) { BINDSVC_CACHE.put(key, new BinderCacheEntry(svc, sc)); }
    }

    /** v130：在指定 binder 上执行主 transact + 可选 sub-binder 链（原 BINDSVC 内联逻辑抽出） */
    private void transactOnBinder(IBinder svc, String[] mainArgs, String chainPart, StringBuilder res) {
        IBinder currentBinder = doTransactOnce(svc, mainArgs, res);
        if (chainPart != null && !chainPart.isEmpty()) {
            res.append("--- Step 2 (on sub-binder) ---\n");
            String[] chainArgs = chainPart.split(" ");
            try {
                int ci = 0;
                while (ci < chainArgs.length) {
                    int c = Integer.parseInt(chainArgs[ci]);
                    ci++;
                    android.os.Parcel d2 = android.os.Parcel.obtain();
                    android.os.Parcel r2 = android.os.Parcel.obtain();
                    try {
                        d2.writeInterfaceToken(currentBinder.getInterfaceDescriptor());
                        while (ci < chainArgs.length) {
                            try { Integer.parseInt(chainArgs[ci]); break; }
                            catch (NumberFormatException e) {
                                String arg = chainArgs[ci];
                                if (arg.startsWith("i:")) d2.writeInt(Integer.parseInt(arg.substring(2)));
                                else if (arg.startsWith("s:")) d2.writeString(arg.substring(2));
                                else if (arg.startsWith("b:")) d2.writeByte(Byte.parseByte(arg.substring(2)));
                                else if (arg.equalsIgnoreCase("true")) d2.writeInt(1);
                                else if (arg.equalsIgnoreCase("false")) d2.writeInt(0);
                                else { try { d2.writeInt(Integer.parseInt(arg)); } catch (NumberFormatException e2) { d2.writeString(arg); } }
                                ci++;
                            }
                        }
                        res.append("Call sub-binder: tr("+c+") "+"\n");
                        boolean ok = currentBinder.transact(c, d2, r2, 0);
                        res.append("  ok="+ok+"\n");
                        if (ok && r2.dataAvail() > 0) {
                            int excC = r2.readInt();
                            if (excC != 0 && excC != -1) res.append("  exc="+excC+"\n");
                            try { String s = r2.readString(); if (s != null) res.append("  -> String: "+s+"\n"); } catch (Exception e2) {}
                            try { r2.setDataPosition(4); int iv = r2.readInt(); res.append("  -> Int: "+iv+"\n"); } catch (Exception e2) {}
                        }
                        r2.recycle(); d2.recycle();
                    } catch (Exception e) {
                        res.append("  err: "+truncate(e.toString(),60)+"\n");
                        try { d2.recycle(); r2.recycle(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                res.append("  chain parse err: "+truncate(e.toString(),80)+"\n");
            }
        }
    }

    /** v130：单次 transact，解析回复（Binder/String/Int/Raw），返回 sub-binder 或当前 binder（格式同 v129） */
    private IBinder doTransactOnce(IBinder svc, String[] args, StringBuilder res) {
        if (args.length < 1) return svc;
        int tCode = Integer.parseInt(args[0]);
        try {
            android.os.Parcel d = android.os.Parcel.obtain();
            android.os.Parcel r = android.os.Parcel.obtain();
            try {
                d.writeInterfaceToken(svc.getInterfaceDescriptor());
                for (int i = 1; i < args.length; i++) {
                    String a = args[i];
                    if (a.startsWith("i:")) d.writeInt(Integer.parseInt(a.substring(2)));
                    else if (a.startsWith("l:")) d.writeLong(Long.parseLong(a.substring(2)));
                    else if (a.startsWith("b:")) d.writeByte(Byte.parseByte(a.substring(2)));
                    else if (a.startsWith("f:")) d.writeFloat(Float.parseFloat(a.substring(2)));
                    else if (a.startsWith("d:")) d.writeDouble(Double.parseDouble(a.substring(2)));
                    else if (a.startsWith("s:")) d.writeString(a.substring(2));
                    else d.writeString(a);
                }
                boolean ok = svc.transact(tCode, d, r, 0);
                res.append("tr("+tCode+"): "+ok+"\n");
                if (ok && r.dataAvail() > 0) {
                    int excCode = 0;
                    int startPos = r.dataPosition();
                    try { excCode = r.readInt(); } catch (Exception e) {}
                    if (excCode != 0 && excCode != -1) {
                        res.append("  -> ExcCode: "+excCode+"\n");
                    }
                    try {
                        IBinder sub = r.readStrongBinder();
                        if (sub != null) {
                            res.append("  -> Binder: "+sub+"\n");
                            try { res.append("  -> Iface: "+sub.getInterfaceDescriptor()+"\n"); } catch (Exception e) {}
                            return sub;
                        }
                    } catch (Exception e) {}
                    try {
                        String s = r.readString();
                        if (s != null) res.append("  -> String: "+s+"\n");
                    } catch (Exception e) {}
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
        return svc; // 取不到 sub-binder 时返回当前 binder
    }

    /** #PLAY <path> [--vol 0-100] — 用 MediaPlayer 走 AudioFlinger 播放，绕开 /dev/snd 的 SELinux 限制。 */
    private void playAudio(String arg, StringBuilder res) {
        String path = arg;
        int volPct = -1;
        int vi = arg.lastIndexOf(" --vol ");
        if (vi > 0) {
            path = arg.substring(0, vi).trim();
            try { volPct = Integer.parseInt(arg.substring(vi + 6).trim()); } catch (Exception ignored) {}
        }
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int target = volPct >= 0 ? Math.max(0, Math.min(max, max * volPct / 100)) : Math.max(1, max * 3 / 4);
            try { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0); } catch (Exception ignored) {}
            stopAudio();
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setDataSource(path);
            mp.prepare();
            mp.start();
            audioPlayer = mp;
            res.append("PLAYING ").append(path).append(" dur=").append(mp.getDuration()).append("ms vol=").append(target).append("/").append(max).append("\n");
        } catch (Exception e) {
            res.append("PLAY FAILED: ").append(e.toString()).append("\n");
        }
    }

    private void stopAudio() {
        if (audioPlayer != null) {
            try { if (audioPlayer.isPlaying()) audioPlayer.stop(); } catch (Exception ignored) {}
            try { audioPlayer.release(); } catch (Exception ignored) {}
            audioPlayer = null;
        }
    }

    /** Legacy com.vox.shellbridge may still hold 8888 after the merged install.
     *  It executes shell commands as its own uid, so ask it to kill itself. */
    private void tryTakeoverLegacyBridge() {
        Socket s = new Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 800);
            s.setSoTimeout(500);
            OutputStream out = s.getOutputStream();
            out.write("killall com.vox.shellbridge\n".getBytes());
            out.flush();
            Thread.sleep(300);
            Log.i(TAG, "takeover: legacy ShellBridge asked to exit");
        } catch (Exception e) {
            Log.i(TAG, "takeover: no legacy bridge (" + e.getMessage() + ")");
        } finally {
            try { s.close(); } catch (IOException ignored) {}
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

                } else if (cmd.equals("#NETDIAG")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== NETDIAG ===\n");
                    res.append("time=").append(new java.util.Date()).append(" epoch=").append(System.currentTimeMillis()).append("\n");
                    try {
                        res.append("NetState ok=").append(NetState.INSTANCE.getOk())
                           .append(" fresh=").append(NetState.INSTANCE.fresh(System.currentTimeMillis(), 15000L))
                           .append(" err=[").append(NetState.INSTANCE.getErr()).append("]")
                           .append(" lastOk=").append(NetState.INSTANCE.getLastOk()).append("\n");
                    } catch (Throwable t) { res.append("NetState: ").append(t).append("\n"); }
                    try {
                        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                        android.net.Network an = cm.getActiveNetwork();
                        android.net.NetworkCapabilities nc = an == null ? null : cm.getNetworkCapabilities(an);
                        res.append("activeNet=").append(an).append("\n");
                        if (nc != null) {
                            res.append("internet=").append(nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET))
                               .append(" validated=").append(nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                               .append(" captive=").append(nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)).append("\n");
                        }
                    } catch (Throwable t) { res.append("cm: ").append(t).append("\n"); }
                    try {
                        javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                            new java.net.URL("https://nas.<PRIVATE_DOMAIN>:8443/api/").openConnection();
                        c.setSSLSocketFactory(HaTrust.INSTANCE.getSocketFactory());
                        c.setConnectTimeout(5000); c.setReadTimeout(7000);
                        c.setRequestMethod("GET");
                        long t0 = System.currentTimeMillis();
                        int code = c.getResponseCode();
                        res.append("https /api/ code=").append(code).append(" in ").append(System.currentTimeMillis() - t0).append("ms\n");
                        c.disconnect();
                    } catch (Throwable t) {
                        res.append("https FAIL ").append(t.getClass().getName()).append(": ").append(truncate(String.valueOf(t.getMessage()), 120)).append("\n");
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

                } else if (cmd.startsWith("#STR16 ")) {
                    String rest = firstLine.trim().substring(7).trim();
                    String[] toks = rest.split("\\s+");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    if (toks.length < 2) {
                        res.append("Usage: #STR16 <path|@pkg> <pattern> [maxHits]\n");
                    } else {
                        String path = toks[0];
                        String pat = toks[1];
                        int max = toks.length > 2 ? Integer.parseInt(toks[2]) : 20;
                        try {
                            if (path.startsWith("@")) {
                                path = getPackageManager().getApplicationInfo(path.substring(1), 0).sourceDir;
                            }
                            res.append("scan ").append(path).append(" for '").append(pat).append("'\n");
                            File f = new File(path);
                            java.util.LinkedHashSet<String> hits = new java.util.LinkedHashSet<String>();
                            boolean zip = false;
                            java.io.FileInputStream fis0 = new java.io.FileInputStream(f);
                            byte[] magic = new byte[4];
                            int mr = fis0.read(magic);
                            fis0.close();
                            zip = mr == 4 && magic[0] == 'P' && magic[1] == 'K';
                            if (zip) {
                                java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f);
                                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                                while (en.hasMoreElements() && hits.size() < max) {
                                    java.util.zip.ZipEntry ze = en.nextElement();
                                    String n = ze.getName();
                                    if (ze.getSize() > 40000000L) continue;
                                    if (!(n.endsWith(".dex") || n.endsWith(".xml") || n.endsWith(".arsc") || n.endsWith(".so") || n.endsWith(".json"))) continue;
                                    byte[] b = readAll(zf.getInputStream(ze), 40000000);
                                    scanBuf(b, pat, hits, max);
                                    res.append("  [").append(n).append("] hits=").append(hits.size()).append("\n");
                                }
                                zf.close();
                            } else {
                                byte[] b = readAll(new java.io.FileInputStream(f), 40000000);
                                scanBuf(b, pat, hits, max);
                            }
                            res.append("--- MATCHES (").append(hits.size()).append(") ---\n");
                            for (String h : hits) res.append(h).append("\n");
                        } catch (Throwable t) {
                            res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage())).append("\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#APKOF ")) {
                    OutputStream netOut = client.getOutputStream();
                    String pkg = firstLine.trim().substring(7).trim();
                    StringBuilder res = new StringBuilder();
                    try {
                        res.append(pkg).append(" -> ").append(getPackageManager().getApplicationInfo(pkg, 0).sourceDir).append("\n");
                    } catch (Throwable t) { res.append("ERR ").append(t).append("\n"); }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#PERMCHK")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String p = firstLine.trim().length() > 9 ? firstLine.trim().substring(9).trim() : "";
                    if (p.isEmpty()) {
                        res.append("Usage: #PERMCHK <permission>\n");
                    } else {
                        try {
                            android.content.pm.PermissionInfo pi = getPackageManager().getPermissionInfo(p, 0);
                            int lvl = pi.protectionLevel;
                            int base = lvl & 0xf;
                            res.append(p).append(" level=0x").append(Integer.toHexString(lvl))
                               .append(" base=").append(base == 0 ? "normal" : base == 1 ? "dangerous" : base == 2 ? "signature" : base == 3 ? "signatureOrSystem" : String.valueOf(base))
                               .append(" priv=").append((lvl & 0x10) != 0)
                               .append(" pkg=").append(pi.packageName).append("\n");
                        } catch (Throwable t) { res.append(p).append(": not defined (").append(t.getClass().getSimpleName()).append(")\n"); }
                        res.append("self=").append(checkSelfPermission(p)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#VEHLISTEN")) {
                    String rest3 = firstLine.trim().length() > 10 ? firstLine.trim().substring(10).trim() : "";
                    int secs3 = 10;
                    try { if (!rest3.isEmpty()) secs3 = Integer.parseInt(rest3); } catch (Throwable t) { }
                    final java.util.List<String> ev3 = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                        final Object[] holder = new Object[1];
                        android.content.ServiceConnection conn = new android.content.ServiceConnection() {
                            public void onServiceConnected(android.content.ComponentName n, android.os.IBinder b) { holder[0] = b; latch.countDown(); }
                            public void onServiceDisconnected(android.content.ComponentName n) { }
                        };
                        android.content.Intent it = new android.content.Intent();
                        it.setComponent(new android.content.ComponentName("com.patac.service", "com.patac.service.PatacService"));
                        boolean ok = bindService(it, conn, android.content.Context.BIND_AUTO_CREATE);
                        latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
                        android.os.IBinder b = (android.os.IBinder) holder[0];
                        res.append("bind=").append(ok).append(" binder=").append(b != null).append("\n");
                        if (b != null) {
                            android.os.Parcel d = android.os.Parcel.obtain();
                            android.os.Parcel r = android.os.Parcel.obtain();
                            d.writeInterfaceToken("patac.manager.IPatacService");
                            d.writeString("PatacVehicleManagerService");
                            b.transact(1, d, r, 0);
                            r.readException();
                            android.os.IBinder child = r.readStrongBinder();
                            res.append("child=").append(child != null).append("\n");
                            if (child != null) {
                                final android.os.Binder lis = new android.os.Binder() {
                                    @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                                        try {
                                            byte[] arr = data.marshall();
                                            StringBuilder sb = new StringBuilder();
                                            for (int i = 0; i < Math.min(arr.length, 80); i++) sb.append(String.format("%02x", arr[i]));
                                            if (ev3.size() < 60) ev3.add("cb code=" + code + " len=" + arr.length + " hex=" + sb);
                                        } catch (Throwable t) { }
                                        return true;
                                    }
                                };
                                android.os.Parcel d2 = android.os.Parcel.obtain();
                                android.os.Parcel r2 = android.os.Parcel.obtain();
                                d2.writeInterfaceToken("patac.manager.vehicle.IPatacVehicle");
                                d2.writeStrongBinder(lis);
                                boolean tr = child.transact(2, d2, r2, 0);
                                res.append("registerCallback code=2 ret=").append(tr).append(" avail=").append(r2.dataAvail()).append("\n");
                                try { r2.readException(); } catch (Throwable t) { res.append("reg exc=").append(t).append("\n"); }
                                Thread.sleep(secs3 * 1000L);
                                res.append("callbacks=").append(ev3.size()).append("\n");
                                int i = 0;
                                for (String s : ev3) { if (i++ >= 20) break; res.append("  ").append(s).append("\n"); }
                            }
                        }
                        try { unbindService(conn); } catch (Throwable t) { }
                    } catch (Throwable t) {
                        Throwable cc = t.getCause() != null ? t.getCause() : t;
                        res.append("ERR ").append(cc.getClass().getName()).append(": ").append(String.valueOf(cc.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#SUBSALL")) {
                    String rest2 = firstLine.trim().length() > 8 ? firstLine.trim().substring(8).trim() : "";
                    int secs2 = 12;
                    try { if (!rest2.isEmpty()) secs2 = Integer.parseInt(rest2); } catch (Throwable t) { }
                    final java.util.List<String> ev2 = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        Class<?> carCls = Class.forName("android.car.Car");
                        Object inst = null;
                        for (java.lang.reflect.Method m : carCls.getMethods()) {
                            if (!m.getName().equals("createCar")) continue;
                            try { inst = m.getParameterTypes().length == 1 ? m.invoke(null, ShellService.this) : m.invoke(null, ShellService.this, null); break; } catch (Throwable t) { }
                        }
                        Object mgr = carCls.getMethod("getCarManager", String.class).invoke(inst, "property");
                        Class<?> cbCls = Class.forName("android.car.hardware.property.CarPropertyManager" + "\u0024" + "CarPropertyEventCallback");
                        Object proxy = java.lang.reflect.Proxy.newProxyInstance(cbCls.getClassLoader(), new Class<?>[]{cbCls}, new java.lang.reflect.InvocationHandler() {
                            public Object invoke(Object o, java.lang.reflect.Method mm, Object[] a) {
                                try {
                                    if (mm.getName().equals("onChangeEvent") && a != null && a.length > 0 && a[0] != null) {
                                        Object pv = a[0];
                                        int pid = ((Integer) pv.getClass().getMethod("getPropertyId").invoke(pv)).intValue();
                                        Object val = pv.getClass().getMethod("getValue").invoke(pv);
                                        if (ev2.size() < 600) ev2.add("0x" + Integer.toHexString(pid) + " = " + val);
                                    }
                                } catch (Throwable t) { }
                                return null;
                            }
                        });
                        java.lang.reflect.Method reg2 = null;
                        for (java.lang.reflect.Method m : mgr.getClass().getMethods()) {
                            if (m.getName().equals("registerCallback") && m.getParameterTypes().length == 3 && m.getParameterTypes()[1] == int.class) { reg2 = m; break; }
                        }
                        if (reg2 == null) { res.append("no registerCallback(cb,int,float)\n"); }
                        else {
                            Object cfgs = mgr.getClass().getMethod("getPropertyList").invoke(mgr);
                            java.util.List<?> l = (java.util.List<?>) cfgs;
                            java.util.List<Integer> ids = new java.util.ArrayList<Integer>();
                            for (Object cfg : l) {
                                try { ids.add((Integer) cfg.getClass().getMethod("getPropertyId").invoke(cfg)); } catch (Throwable t) { }
                            }
                            for (Integer id : ids) { try { reg2.invoke(mgr, proxy, id.intValue(), 25f); } catch (Throwable t) { } }
                            res.append("subscribed ").append(ids.size()).append(" props, secs=").append(secs2).append("\n");
                            Thread.sleep(secs2 * 1000L);
                            for (Integer id : ids) {
                                try {
                                    for (java.lang.reflect.Method m : mgr.getClass().getMethods()) {
                                        if (m.getName().equals("unregisterCallback") && m.getParameterTypes().length == 2) { m.invoke(mgr, proxy, id.intValue()); break; }
                                    }
                                } catch (Throwable t) { }
                            }
                        }
                        res.append("events=").append(ev2.size()).append("\n");
                        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<String>();
                        for (String s : ev2) { uniq.add(s.split(" = ")[0]); if (uniq.size() > 40) break; }
                        res.append("changed_ids=").append(uniq).append("\n");
                        int n2 = 0;
                        for (String s : ev2) { if (n2++ >= 30) { res.append("...\n"); break; } res.append("  ").append(s).append("\n"); }
                    } catch (Throwable t) {
                        Throwable cc = t.getCause() != null ? t.getCause() : t;
                        res.append("ERR ").append(cc.getClass().getName()).append(": ").append(String.valueOf(cc.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#SUBS")) {
                    String rest = firstLine.trim().length() > 5 ? firstLine.trim().substring(5).trim() : "";
                    String[] tk = rest.isEmpty() ? new String[0] : rest.split("\\s+");
                    int propId = -1, secs = 12;
                    for (String tok : tk) {
                        try { propId = tok.startsWith("0x") ? Integer.parseInt(tok.substring(2), 16) : Integer.parseInt(tok); } catch (Throwable t) { }
                    }
                    if (tk.length > 1) { try { secs = Integer.parseInt(tk[1]); } catch (Throwable t) { } }
                    final java.util.List<String> ev = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        Class<?> carCls = Class.forName("android.car.Car");
                        Object inst = null;
                        for (java.lang.reflect.Method m : carCls.getMethods()) {
                            if (!m.getName().equals("createCar")) continue;
                            try { inst = m.getParameterTypes().length == 1 ? m.invoke(null, ShellService.this) : m.invoke(null, ShellService.this, null); break; } catch (Throwable t) { }
                        }
                        Object mgr = carCls.getMethod("getCarManager", String.class).invoke(inst, "property");
                        final Class<?> cbCls = Class.forName("android.car.hardware.property.CarPropertyManager" + "\u0024" + "CarPropertyEventCallback");
                        Object proxy = java.lang.reflect.Proxy.newProxyInstance(cbCls.getClassLoader(), new Class<?>[]{cbCls}, new java.lang.reflect.InvocationHandler() {
                            public Object invoke(Object o, java.lang.reflect.Method mm, Object[] a) {
                                try {
                                    if (mm.getName().equals("onChangeEvent") && a != null && a.length > 0 && a[0] != null) {
                                        Object pv = a[0];
                                        int pid = ((Integer) pv.getClass().getMethod("getPropertyId").invoke(pv)).intValue();
                                        Object val = pv.getClass().getMethod("getValue").invoke(pv);
                                        if (ev.size() < 400) ev.add("0x" + Integer.toHexString(pid) + " = " + val);
                                    }
                                } catch (Throwable t) { }
                                return null;
                            }
                        });
                        java.lang.reflect.Method reg = null;
                        for (java.lang.reflect.Method m : mgr.getClass().getMethods()) {
                            if (!m.getName().equals("registerCallback")) continue;
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts[0] != cbCls) continue;
                            if (propId < 0) {
                                if (pts.length == 2 && pts[1] == float.class) { reg = m; break; }
                            } else {
                                if (pts.length >= 3 && pts[1] == int.class) { reg = m; break; }
                            }
                        }
                        if (reg == null) {
                            for (java.lang.reflect.Method m : mgr.getClass().getMethods()) {
                                if (!m.getName().equals("registerCallback")) continue;
                                Class<?>[] pts = m.getParameterTypes();
                                if (pts.length >= 2 && pts[0] == cbCls) { reg = m; break; }
                            }
                        }
                        if (reg == null) { res.append("no registerCallback\n"); }
                        else {
                            Class<?>[] pts = reg.getParameterTypes();
                            Object[] args = new Object[pts.length];
                            args[0] = proxy;
                            for (int i = 1; i < pts.length; i++) {
                                if (pts[i] == float.class) args[i] = 50f;
                                else if (pts[i] == int.class) args[i] = propId;
                                else args[i] = null;
                            }
                            reg.invoke(mgr, args);
                            res.append("subscribed via ").append(reg.getName()).append("/").append(pts.length).append(" secs=").append(secs).append("\n");
                            Thread.sleep(secs * 1000L);
                            try {
                                for (java.lang.reflect.Method m : mgr.getClass().getMethods()) {
                                    if (m.getName().equals("unregisterCallback") && m.getParameterTypes().length == 1) { m.invoke(mgr, proxy); break; }
                                }
                            } catch (Throwable t) { }
                        }
                        res.append("events=").append(ev.size()).append("\n");
                        int n = 0;
                        for (String s : ev) { if (n++ >= 40) { res.append("...\n"); break; } res.append("  ").append(s).append("\n"); }
                    } catch (Throwable t) {
                        Throwable cc = t.getCause() != null ? t.getCause() : t;
                        res.append("ERR ").append(cc.getClass().getName()).append(": ").append(String.valueOf(cc.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#PROP ")) {
                    String rest = firstLine.trim().substring(6).trim();
                    String[] tk = rest.split("\\s+");
                    int pid = tk[0].startsWith("0x") ? Integer.parseInt(tk[0].substring(2), 16) : Integer.parseInt(tk[0]);
                    int aid = tk.length > 1 ? Integer.parseInt(tk[1]) : 0;
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        Class<?> carCls = Class.forName("android.car.Car");
                        Object inst = null;
                        for (java.lang.reflect.Method m : carCls.getMethods()) {
                            if (!m.getName().equals("createCar")) continue;
                            try { inst = m.getParameterTypes().length == 1 ? m.invoke(null, ShellService.this) : m.invoke(null, ShellService.this, null); break; } catch (Throwable t) { }
                        }
                        Object mgr = carCls.getMethod("getCarManager", String.class).invoke(inst, "property");
                        res.append("mgr=").append(mgr != null).append(" pid=0x").append(Integer.toHexString(pid)).append(" area=").append(aid).append("\n");
                        for (String mn : new String[]{"getFloatProperty","getIntProperty","getProperty"}) {
                            try {
                                java.lang.reflect.Method mm = null;
                                for (java.lang.reflect.Method m : mgr.getClass().getMethods()) {
                                    if (m.getName().equals(mn) && m.getParameterTypes().length == 2 && m.getParameterTypes()[0] == int.class) { mm = m; break; }
                                }
                                if (mm == null) { res.append(mn).append(": no 2-arg\n"); continue; }
                                res.append(mn).append("=").append(mm.invoke(mgr, pid, aid)).append("\n");
                            } catch (Throwable t) {
                                Throwable cc = t.getCause() != null ? t.getCause() : t;
                                res.append(mn).append(" ERR ").append(cc.getClass().getSimpleName()).append(": ").append(String.valueOf(cc.getMessage())).append("\n");
                            }
                        }
                    } catch (Throwable t) {
                        res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#VCL")) {
                    String rest = firstLine.trim().length() > 4 ? firstLine.trim().substring(4).trim() : "";
                    String[] tk = rest.isEmpty() ? new String[0] : rest.split("\\s+");
                    int pid = tk.length > 0 ? (tk[0].startsWith("0x") ? Integer.parseInt(tk[0].substring(2), 16) : Integer.parseInt(tk[0])) : 0x11600207;
                    int aid = tk.length > 1 ? Integer.parseInt(tk[1]) : 0;
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        dalvik.system.DexClassLoader cl = new dalvik.system.DexClassLoader(
                                "/system/framework/patacframework.jar", null, null, ShellService.class.getClassLoader());
                        Class<?> c = cl.loadClass("com.patac.vehicle.VehicleController");
                        res.append("class=").append(c.getName()).append(" loader=").append(c.getClassLoader()).append("\n");
                        res.append("declaredMethods=").append(c.getDeclaredMethods().length).append("\n");
                        Object vc = c.getMethod("getInstance").invoke(null);
                        res.append("inst=").append(vc != null).append("\n");
                        if (vc != null) {
                            res.append("float=").append(c.getMethod("getFloat", int.class, int.class).invoke(vc, pid, aid)).append("\n");
                            res.append("int=").append(c.getMethod("getInt", int.class, int.class).invoke(vc, pid, aid)).append("\n");
                        }
                    } catch (Throwable t) {
                        Throwable cc = t.getCause() != null ? t.getCause() : t;
                        res.append("ERR ").append(cc.getClass().getName()).append(": ").append(String.valueOf(cc.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#VCF")) {
                    String rest = firstLine.trim().length() > 4 ? firstLine.trim().substring(4).trim() : "";
                    String[] tk = rest.isEmpty() ? new String[0] : rest.split("\\s+");
                    int pid = tk.length > 0 ? (tk[0].startsWith("0x") ? Integer.parseInt(tk[0].substring(2), 16) : Integer.parseInt(tk[0])) : 0x11600207;
                    int aid = tk.length > 1 ? Integer.parseInt(tk[1]) : 0;
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        Object vc = com.patac.vehicle.VehicleController.getInstance();
                        res.append("vc=").append(vc != null).append(" pid=0x").append(Integer.toHexString(pid)).append("\n");
                        if (vc instanceof com.patac.vehicle.VehicleController) {
                            com.patac.vehicle.VehicleController c = (com.patac.vehicle.VehicleController) vc;
                            try { res.append("float=").append(c.getFloat(pid, aid)).append("\n"); } catch (Throwable t) { res.append("floatERR=").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n"); }
                            try { res.append("int=").append(c.getInt(pid, aid)).append("\n"); } catch (Throwable t) { res.append("intERR=").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n"); }
                        }
                    } catch (Throwable t) {
                        Throwable cc = t.getCause() != null ? t.getCause() : t;
                        res.append("ERR ").append(cc.getClass().getName()).append(": ").append(String.valueOf(cc.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#RPM")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        Object pc = com.patac.vehicle.PowertainController.getInstance();
                        res.append("powertain=").append(pc != null).append("\n");
                        if (pc instanceof com.patac.vehicle.PowertainController) {
                            com.patac.vehicle.VehicleController.Result r = ((com.patac.vehicle.PowertainController) pc).getCurrentEngineSpeed();
                            res.append("result=").append(r).append("\n");
                            if (r != null) {
                                res.append("state=").append(r.getState()).append("\n");
                                res.append("avail=").append(r.isValueAvailable()).append("\n");
                                res.append("value=").append(r.getValue()).append("\n");
                            }
                            com.patac.vehicle.VehicleController.Result g = ((com.patac.vehicle.PowertainController) pc).getCurrentlySelectedGear();
                            if (g != null) res.append("gear=").append(g.getValue()).append(" avail=").append(g.isValueAvailable()).append("\n");
                            res.append("isPark=").append(((com.patac.vehicle.PowertainController) pc).isParkGearSelected()).append("\n");
                        }
                    } catch (Throwable t) {
                        Throwable cc = t.getCause() != null ? t.getCause() : t;
                        res.append("ERR ").append(cc.getClass().getName()).append(": ").append(String.valueOf(cc.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#VSTATE")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        Class<?> vs = Class.forName("com.vox.castsink.VehicleState");
                        Object inst = vs.getField("INSTANCE").get(null);
                        String[] names = {"speed","rpm","gear","gearNum","carSpeed","carRpm","carGear","fuelL","rangeKm","ignition","parkingBrake","canCount","live"};
                        for (String n : names) {
                            try {
                                String cap = Character.toUpperCase(n.charAt(0)) + n.substring(1);
                                Object v = vs.getMethod("get" + cap).invoke(inst);
                                res.append(n).append("=").append(v).append("\n");
                            } catch (Throwable t) {
                                res.append(n).append("=?").append("\n");
                            }
                        }
                        try {
                            Class<?> cp = Class.forName("com.vox.castsink.VehicleState$CarProps");
                            Object cpi = cp.getField("INSTANCE").get(null);
                            res.append("carPropsDead=").append(cp.getMethod("isDead").invoke(cpi)).append("\n");
                            res.append("carPropsSubscribed=").append(cp.getMethod("isSubOk").invoke(cpi)).append("\n");
                        } catch (Throwable t) { res.append("carPropsDead=?").append("\n"); }
                    } catch (Throwable t) {
                        res.append("ERR ").append(t).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#CARALL")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append(carAllProbe(ShellService.this)).append("\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#PROPS")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append(carPropsProbe(ShellService.this)).append("\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if ((cmd.startsWith("#BINDSVC ") && cmd.trim().substring(9).split(" ")[0].contains("/")) || cmd.startsWith("#BINSCAN ")) {
                    boolean scan = cmd.startsWith("#BINSCAN ");
                    String rest = firstLine.trim().substring(9).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String[] toks = rest.split("\\s+");
                    if (toks.length < 1 || toks[0].length() == 0) {
                        res.append("Usage: #BINDSVC <pkg>/<cls> [--code=N] [--ints=a,b] [--str=s]\n");
                        res.append("       #BINSCAN <pkg>/<cls> [maxCode]\n");
                    } else {
                        String comp = toks[0];
                        int slash = comp.indexOf('/');
                        final String pkg = slash > 0 ? comp.substring(0, slash) : comp;
                        final String cls = slash > 0 ? comp.substring(slash + 1) : comp;
                        int code = 0;
                        String ints = "";
                        String strArg = null;
                        String bytesArg = null;
                        boolean writeBinder = false;
                        boolean writeBinder2 = false;
                        boolean writeNullBinder = false;
                        int maxCode = 25;
                        for (int i = 1; i < toks.length; i++) {
                            String t = toks[i];
                            if (t.startsWith("--code=")) { try { code = Integer.parseInt(t.substring(7)); } catch (Throwable x) { } }
                            else if (t.startsWith("--ints=")) ints = t.substring(7);
                            else if (t.startsWith("--str=")) strArg = t.substring(6);
                            else if (t.startsWith("--bytes=")) bytesArg = t.substring(8);
                            else if (t.equals("--binder")) writeBinder = true;
                            else if (t.equals("--binder2")) writeBinder2 = true;
                            else if (t.equals("--nullb")) writeNullBinder = true;
                            else if (scan && t.matches("\\d+")) { try { maxCode = Integer.parseInt(t); } catch (Throwable x) { } }
                        }
                        final CountDownLatch latch = new CountDownLatch(1);
                        final Object[] holder = new Object[1];
                        ServiceConnection conn = new ServiceConnection() {
                            public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
                            public void onServiceDisconnected(ComponentName n) { }
                        };
                        try {
                            Intent it = new Intent();
                            it.setComponent(new ComponentName(pkg, cls));
                            res.append("binding... ").append(pkg).append("/").append(cls).append("\n");
                            netOut.write(res.toString().getBytes());
                            netOut.flush();
                            res.setLength(0);
                            // bindService 会在服务进程创建/onBind 上同步阻塞；放独立线程并限时
                            final Intent fit = it;
                            final boolean[] bindRes = new boolean[1];
                            final ServiceConnection fconn = conn;
                            Thread bt = new Thread(new Runnable() {
                                public void run() {
                                    try { bindRes[0] = ShellService.this.bindService(fit, fconn, android.content.Context.BIND_AUTO_CREATE); }
                                    catch (Throwable t) { }
                                }
                            });
                            bt.start();
                            try { bt.join(8000); } catch (Throwable t) { }
                            if (bt.isAlive()) {
                                res.append("bind BLOCKED >8s: 服务进程起不来/onBind 卡死（常见于维护模式下 ECU 未就绪）\n");
                                netOut.write(res.toString().getBytes());
                                netOut.flush();
                                res.setLength(0);
                                throw new RuntimeException("bind-blocked");
                            }
                            res.append("bind=").append(bindRes[0]).append(" target=").append(pkg).append("/").append(cls).append("\n");
                            netOut.write(res.toString().getBytes());
                            netOut.flush();
                            res.setLength(0);
                            latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
                            IBinder b = (IBinder) holder[0];
                            res.append("binder=").append(b != null).append("\n");
                            if (b == null) {
                                res.append("no binder: not exported / permission denied / service disabled\n");
                            } else {
                                String desc = null;
                                // descriptor 走独立线程：服务初始化慢也不会吞掉回包
                                final IBinder bf = b;
                                final String[] dh = new String[1];
                                Thread dt = new Thread(new Runnable() {
                                    public void run() {
                                        try { dh[0] = bf.getInterfaceDescriptor(); }
                                        catch (Throwable t) { dh[0] = "ERR:" + t.getClass().getSimpleName(); }
                                    }
                                });
                                dt.start();
                                try { dt.join(4000); } catch (Throwable t) { }
                                desc = dt.isAlive() ? "TIMEOUT(service init slow)" : dh[0];
                                res.append("descriptor=").append(desc).append("\n");
                                netOut.write(res.toString().getBytes());
                                netOut.flush();
                                res.setLength(0);
                                int start = scan ? 1 : code;
                                int end = scan ? maxCode : code;
                                for (int c = start; c <= end; c++) {
                                    if (c <= 0) continue;
                                    android.os.Parcel data = android.os.Parcel.obtain();
                                    android.os.Parcel reply = android.os.Parcel.obtain();
                                    try {
                                        if (desc != null) data.writeInterfaceToken(desc);
                                        if (ints.length() > 0) {
                                            for (String s : ints.split(",")) if (s.length() > 0) data.writeInt(Integer.parseInt(s));
                                        }
                                        if (strArg != null) data.writeString(strArg);
                                        if (bytesArg != null && bytesArg.length() > 0) {
                                            byte[] bb = new byte[bytesArg.length() / 2];
                                            for (int k = 0; k < bb.length; k++) {
                                                bb[k] = (byte) Integer.parseInt(bytesArg.substring(k * 2, k * 2 + 2), 16);
                                            }
                                            data.writeByteArray(bb);
                                        }
                                        if (writeBinder) data.writeStrongBinder(new android.os.Binder());
                                        if (writeNullBinder) data.writeStrongBinder(null);
                                        if (writeBinder2) data.writeStrongBinder(new android.os.Binder());
                                        boolean tr = b.transact(c, data, reply, 0);
                                        int avail = reply.dataAvail();
                                        byte[] bytes = null;
                                        try { bytes = reply.marshall(); } catch (Throwable t) { }
                                        String hex = bytes == null ? "" : hexOf(bytes, 0, Math.min(bytes.length, 64));
                                        res.append("  code=").append(c).append(" ret=").append(tr).append(" avail=").append(avail);
                                        if (hex.length() > 0) res.append(" hex=").append(hex);
                                        res.append("\n");
                                    } catch (Throwable t) {
                                        Throwable cc = t.getCause() != null ? t.getCause() : t;
                                        res.append("  code=").append(c).append(" EX ").append(cc.getClass().getSimpleName()).append(": ").append(truncate(String.valueOf(cc.getMessage()), 90)).append("\n");
                                    } finally {
                                        data.recycle();
                                        reply.recycle();
                                        netOut.write(res.toString().getBytes());
                                        netOut.flush();
                                        res.setLength(0);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(truncate(String.valueOf(t.getMessage()), 160)).append("\n");
                        } finally {
                            try { unbindService(conn); } catch (Throwable t) { }
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#ADALISTEN") || cmd.startsWith("#ADALISTEN ")) {
                    String rest = firstLine.trim().length() > 10 ? firstLine.trim().substring(10).trim() : "";
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    int secs = 12;
                    String[] tk = rest.length() == 0 ? new String[0] : rest.split("\\s+");
                    if (tk.length > 0) { try { secs = Integer.parseInt(tk[0]); } catch (Throwable t) { } }
                    int onlyCode = 0;
                    if (tk.length > 1) { try { onlyCode = Integer.parseInt(tk[1]); } catch (Throwable t) { } }
                    if (secs > 45) secs = 45;
                    String pkg = "com.gm.cn.adasservice";
                    String cls = "com.gm.cn.adasservice.AdasService";
                    res.append("adalisten start (secs=").append(secs).append(")\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();
                    res.setLength(0);
                    AdasCb cbTmp = null;
                    try { cbTmp = new AdasCb(); }
                    catch (Throwable t) {
                        res.append("cb-create FAILED ").append(t.getClass().getName()).append(": ").append(String.valueOf(t.getMessage())).append("\n");
                        netOut.write(res.toString().getBytes());
                        netOut.flush();
                        res.setLength(0);
                    }
                    final AdasCb cb = cbTmp;
                    final CountDownLatch latch = new CountDownLatch(1);
                    final Object[] holder = new Object[1];
                    ServiceConnection conn = new ServiceConnection() {
                        public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
                        public void onServiceDisconnected(ComponentName n) { }
                    };
                    try {
                        Intent it = new Intent();
                        it.setComponent(new ComponentName(pkg, cls));
                        res.append("binding ").append(pkg).append("/").append(cls).append("\n");
                        netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                        boolean ok = bindService(it, conn, android.content.Context.BIND_AUTO_CREATE);
                        latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
                        IBinder b = (IBinder) holder[0];
                        res.append("bind=").append(ok).append(" binder=").append(b != null).append("\n");
                        if (b == null) {
                            res.append("no binder\n");
                        } else {
                            String desc = b.getInterfaceDescriptor();
                            res.append("descriptor=").append(desc).append("\n");
                            for (int c = 1; c <= 20; c++) {
                                if (onlyCode > 0 && c != onlyCode) continue;
                                android.os.Parcel d = android.os.Parcel.obtain();
                                android.os.Parcel r = android.os.Parcel.obtain();
                                try {
                                    if (desc != null) d.writeInterfaceToken(desc);
                                    d.writeStrongBinder(cb);
                                    boolean tr = b.transact(c, d, r, 0);
                                    res.append("  reg code=").append(c).append(" ret=").append(tr).append(" avail=").append(r.dataAvail()).append("\n");
                                } catch (Throwable t) {
                                    Throwable cc = t.getCause() != null ? t.getCause() : t;
                                    res.append("  reg code=").append(c).append(" EX ").append(cc.getClass().getSimpleName()).append(": ").append(truncate(String.valueOf(cc.getMessage()), 70)).append("\n");
                                } finally {
                                    d.recycle(); r.recycle();
                                    netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                                }
                            }
                            res.append("listening ").append(secs).append("s ...\n");
                            netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                            Thread.sleep(secs * 1000L);
                            res.append("CALLBACKS hits=").append(cb.hits.get()).append("\n");
                            for (String s : cb.samples) res.append("  ").append(s).append("\n");
                        }
                    } catch (Throwable t) {
                        res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(truncate(String.valueOf(t.getMessage()), 140)).append("\n");
                    } finally {
                        try { unbindService(conn); } catch (Throwable t) { }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#ADALISTENBG")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String rest = firstLine.trim().length() > 12 ? firstLine.trim().substring(12).trim() : "";
                    int secs = 300;
                    if (rest.length() > 0) { try { secs = Integer.parseInt(rest.split("\\s+")[0]); } catch (Throwable t) { } }
                    if (secs > 1800) secs = 1800;
                    final int fsecs = secs;
                    final android.content.Context ctx = ShellService.this;
                    Thread t = new Thread(new Runnable() {
                        public void run() { adasListenBg(ctx, fsecs); }
                    });
                    t.setDaemon(true);
                    t.start();
                    res.append("ADALISTENBG started secs=").append(fsecs).append(" -> /sdcard/Download/adu_cb.txt\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#ADUDUMP")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String rest = firstLine.trim().length() > 8 ? firstLine.trim().substring(8).trim() : "";
                    int n = 10;
                    if (rest.length() > 0) { try { n = Integer.parseInt(rest); } catch (Throwable t) { } }
                    if (n > 40) n = 40;
                    try {
                        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/sdcard/Download/adu_cb.txt"));
                        java.util.ArrayList<String> all = new java.util.ArrayList<String>();
                        String ln;
                        while ((ln = br.readLine()) != null) {
                            all.add(ln);
                            if (all.size() > 400) all.remove(0);
                        }
                        br.close();
                        res.append("total_lines=").append(all.size()).append("\n");
                        int from = Math.max(0, all.size() - n);
                        for (int i = from; i < all.size(); i++) res.append(all.get(i)).append("\n");
                    } catch (Throwable t) {
                        res.append("no file: ").append(t.getClass().getSimpleName()).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#ADAPROP")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String rest = firstLine.trim().length() > 8 ? firstLine.trim().substring(8).trim() : "";
                    int secs = 300;
                    if (rest.length() > 0) { try { secs = Integer.parseInt(rest.split("\\s+")[0]); } catch (Throwable t) { } }
                    if (secs > 1800) secs = 1800;
                    final int fs = secs;
                    final android.content.Context c0 = ShellService.this;
                    Thread t = new Thread(new Runnable() {
                        public void run() { adasPropBg(c0, fs); }
                    });
                    t.setDaemon(true);
                    t.start();
                    res.append("ADAPROP started secs=").append(fs).append(" -> /sdcard/Download/adu_prop.txt\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#ADUCLEAR")) {
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    java.io.File f2 = new java.io.File("/sdcard/Download/adu_cb.txt");
                    res.append(f2.exists() ? ("deleted=" + f2.delete()) : "not exists").append("\n");
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#STREAMSCAN ")) {
                    String rest = firstLine.trim().substring(12).trim();
                    String[] tk = rest.split("\\s+");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    if (tk.length < 2) {
                        res.append("Usage: #STREAMSCAN <path|@pkg> <pattern> [maxHits]\n");
                    } else {
                        String path = tk[0];
                        String pat = tk[1];
                        int max = 20;
                        if (tk.length > 2) { try { max = Integer.parseInt(tk[2]); } catch (Throwable t) { } }
                        if (max > 80) max = 80;
                        java.util.LinkedHashSet<String> hits = new java.util.LinkedHashSet<String>();
                        try {
                            if (path.startsWith("@")) path = getPackageManager().getApplicationInfo(path.substring(1), 0).sourceDir;
                            File f3 = new File(path);
                            res.append("streamscan ").append(path).append(" '").append(pat).append("'\n");
                            java.io.FileInputStream fis = new java.io.FileInputStream(f3);
                            byte[] magic = new byte[4];
                            int mr = fis.read(magic);
                            fis.close();
                            boolean zip = mr == 4 && magic[0] == 'P' && magic[1] == 'K';
                            if (zip) {
                                java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f3);
                                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                                while (en.hasMoreElements() && hits.size() < max) {
                                    java.util.zip.ZipEntry ze = en.nextElement();
                                    String n = ze.getName();
                                    if (!(n.endsWith(".dex") || n.endsWith(".xml") || n.endsWith(".arsc") || n.endsWith(".json"))) continue;
                                    scanStream(zf.getInputStream(ze), pat, hits, max);
                                    res.append("  [").append(n).append("] hits=").append(hits.size()).append("\n");
                                    netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                                }
                                zf.close();
                            } else {
                                scanStream(new java.io.FileInputStream(f3), pat, hits, max);
                            }
                            res.append("--- HITS (").append(hits.size()).append(") ---\n");
                            for (String h : hits) res.append(h).append("\n");
                        } catch (Throwable t) {
                            res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#LOCATE ")) {
                    String rest = firstLine.trim().substring(8).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String[] toks = rest.split("\\s+");
                    if (toks.length < 1 || toks[0].length() == 0) {
                        res.append("Usage: #LOCATE <svcName> [--child=N] [--ints=a,b] [--str=s]\n");
                    } else {
                        String name = toks[0];
                        int childCode = 0;
                        String ints = "";
                        String strArg = null;
                        String strsArg = null;
                        for (int i = 1; i < toks.length; i++) {
                            String t = toks[i];
                            if (t.startsWith("--child=")) { try { childCode = Integer.parseInt(t.substring(8)); } catch (Throwable x) { } }
                            else if (t.startsWith("--scan=")) { try { childCode = -Integer.parseInt(t.substring(7)); } catch (Throwable x) { } }
                            else if (t.startsWith("--ints=")) ints = t.substring(7);
                            else if (t.startsWith("--strs=")) strsArg = t.substring(7);
                            else if (t.startsWith("--str=")) strArg = t.substring(6);
                        }
                        final CountDownLatch latch = new CountDownLatch(1);
                        final Object[] holder = new Object[1];
                        ServiceConnection conn = new ServiceConnection() {
                            public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
                            public void onServiceDisconnected(ComponentName n) { }
                        };
                        try {
                            Intent it = new Intent();
                            it.setComponent(new ComponentName("com.patac.service", "com.patac.service.PatacService"));
                            boolean ok = bindService(it, conn, android.content.Context.BIND_AUTO_CREATE);
                            res.append("bind=").append(ok).append("\n");
                            netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                            IBinder b = (IBinder) holder[0];
                            if (b == null) {
                                res.append("no binder\n");
                            } else {
                                String desc = null;
                                try { desc = b.getInterfaceDescriptor(); } catch (Throwable t) { }
                                res.append("desc=").append(desc).append("\n");
                                android.os.Parcel d = android.os.Parcel.obtain();
                                android.os.Parcel r = android.os.Parcel.obtain();
                                IBinder child = null;
                                try {
                                    d.writeInterfaceToken("patac.manager.IPatacService");
                                    d.writeString(name);
                                    boolean tr = b.transact(1, d, r, 0);
                                    int avail = r.dataAvail();
                                    int exc = -1;
                                    try { exc = r.readInt(); } catch (Throwable t) { }
                                    if (exc == 0) { try { child = r.readStrongBinder(); } catch (Throwable t) { } }
                                    res.append("locate ").append(name).append(" ret=").append(tr).append(" exc=").append(exc).append(" avail=").append(avail).append("\n");
                                } catch (Throwable t) {
                                    res.append("locate EX ").append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage())).append("\n");
                                } finally { d.recycle(); r.recycle(); }
                                netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                                if (child == null) {
                                    res.append("child=null\n");
                                } else {
                                    String cd = null;
                                    try { cd = child.getInterfaceDescriptor(); } catch (Throwable t) { }
                                    res.append("child descriptor=").append(cd).append("\n");
                                    int cEnd = childCode > 0 ? childCode : -childCode;
                                    int cStart = childCode > 0 ? childCode : 1;
                                    int cStep = 1;
                                    for (int ccode = cStart; ccode <= cEnd; ccode += cStep) {
                                        android.os.Parcel d2 = android.os.Parcel.obtain();
                                        android.os.Parcel r2 = android.os.Parcel.obtain();
                                        try {
                                            if (cd != null) d2.writeInterfaceToken(cd);
                                            if (ints.length() > 0) {
                                                for (String s : ints.split(",")) if (s.length() > 0) d2.writeInt(Integer.parseInt(s));
                                            }
                                            if (strsArg != null) {
                                                for (String s : strsArg.split("\\|", -1)) d2.writeString(s);
                                            } else if (strArg != null) d2.writeString(strArg);
                                            boolean tr2 = child.transact(ccode, d2, r2, 0);
                                            byte[] bb = null;
                                            try { bb = r2.marshall(); } catch (Throwable t) { }
                                            res.append("child code=").append(ccode).append(" ret=").append(tr2).append(" avail=").append(r2.dataAvail());
                                            if (bb != null && bb.length > 0) res.append(" hex=").append(hexOf(bb, 0, Math.min(bb.length, 90)));
                                            res.append("\n");
                                        } catch (Throwable t) {
                                            Throwable cc = t.getCause() != null ? t.getCause() : t;
                                            res.append("child EX ").append(cc.getClass().getSimpleName()).append(": ").append(String.valueOf(cc.getMessage())).append("\n");
                                        } finally { d2.recycle(); r2.recycle(); }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage())).append("\n");
                        } finally {
                            try { unbindService(conn); } catch (Throwable t) { }
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#PATMETHODS ")) {
                    String cname = firstLine.trim().substring(11).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        Class<?> c = Class.forName(cname);
                        res.append("class=").append(c.getName()).append(" loader=").append(c.getClassLoader()).append("\n");
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            StringBuilder sb = new StringBuilder("  ");
                            int mod = m.getModifiers();
                            sb.append(java.lang.reflect.Modifier.isStatic(mod) ? "static " : "")
                              .append(java.lang.reflect.Modifier.isPublic(mod) ? "public " : (java.lang.reflect.Modifier.isPrivate(mod) ? "private " : "pkg "))
                              .append(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append("(");
                            Class<?>[] pts = m.getParameterTypes();
                            for (int i = 0; i < pts.length; i++) { if (i > 0) sb.append(", "); sb.append(pts[i].getSimpleName()); }
                            sb.append(")");
                            res.append(sb).append("\n");
                        }
                    } catch (Throwable t) {
                        res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#PATCALL ")) {
                    // #PATCALL <class> <method>  -> invoke on getInstance() singleton, print result
                    String rest = firstLine.trim().substring(9).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String[] tk = rest.split("\\s+");
                    try {
                        Class<?> c = Class.forName(tk[0]);
                        Object inst = null;
                        try { inst = c.getMethod("getInstance").invoke(null); } catch (Throwable t) { }
                        res.append("inst=").append(inst != null).append("\n");
                        java.lang.reflect.Method mm = null;
                        for (java.lang.reflect.Method m : c.getMethods()) {
                            if (m.getName().equals(tk[1]) && m.getParameterTypes().length == 0) { mm = m; break; }
                        }
                        if (mm == null) { res.append("no such method\n"); }
                        else {
                            Object rv = java.lang.reflect.Modifier.isStatic(mm.getModifiers()) ? mm.invoke(null) : mm.invoke(inst);
                            res.append("ret=").append(String.valueOf(rv)).append("\n");
                            if (rv != null) {
                                for (java.lang.reflect.Method g : rv.getClass().getMethods()) {
                                    String gn = g.getName();
                                    if (g.getParameterTypes().length == 0 && (gn.equals("getValue") || gn.equals("getResult") || gn.equals("getData") || gn.equals("toString"))) {
                                        try { res.append("  ").append(gn).append("()=").append(String.valueOf(g.invoke(rv))).append("\n"); } catch (Throwable t) { }
                                    }
                                }
                                for (java.lang.reflect.Field f : rv.getClass().getFields()) {
                                    try { res.append("  .").append(f.getName()).append("=").append(String.valueOf(f.get(rv))).append("\n"); } catch (Throwable t) { }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Throwable cc = t.getCause() != null ? t.getCause() : t;
                        res.append("ERR ").append(cc.getClass().getSimpleName()).append(": ").append(String.valueOf(cc.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#VC ")) {
                    // #VC <propIdDec|0x..> <areaId>  -> com.patac.vehicle.VehicleController.getFloat/getInt
                    String rest = firstLine.trim().substring(4).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String[] tk = rest.split("\\s+");
                    try {
                        int pid = tk.length > 0 ? (tk[0].startsWith("0x") ? Integer.parseInt(tk[0].substring(2), 16) : Integer.parseInt(tk[0])) : 0;
                        int aid = tk.length > 1 ? Integer.parseInt(tk[1]) : 0;
                        Class<?> vc = Class.forName("com.patac.vehicle.VehicleController");
                        Object inst = vc.getMethod("getInstance").invoke(null);
                        res.append("vc=").append(inst != null).append(" pid=0x").append(Integer.toHexString(pid)).append(" area=").append(aid).append("\n");
                        try {
                            Object v = vc.getMethod("getFloat", int.class, int.class).invoke(inst, pid, aid);
                            res.append("float=").append(v).append("\n");
                        } catch (Throwable t1) { res.append("floatERR=").append(String.valueOf(t1.getCause() != null ? t1.getCause() : t1)).append("\n"); }
                        try {
                            Object v = vc.getMethod("getInt", int.class, int.class).invoke(inst, pid, aid);
                            res.append("int=").append(v).append("\n");
                        } catch (Throwable t2) { res.append("intERR=").append(String.valueOf(t2.getCause() != null ? t2.getCause() : t2)).append("\n"); }
                    } catch (Throwable t) {
                        res.append("VCERR ").append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage())).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#PULL ")) {
                    String rest = firstLine.trim().substring(6).trim();
                    String[] tk = rest.split("\\s+");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    if (tk.length < 1 || tk[0].length() == 0) {
                        res.append("Usage: #PULL <path> [offset] [len]\n");
                    } else {
                        String path = tk[0];
                        long off = 0;
                        long len = 768000L;
                        if (tk.length > 1) { try { off = Long.parseLong(tk[1]); } catch (Throwable t) { } }
                        if (tk.length > 2) { try { len = Long.parseLong(tk[2]); } catch (Throwable t) { } }
                        if (len > 1048576L) len = 1048576L;
                        try {
                            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path, "r");
                            long total = raf.length();
                            if (off > total) off = total;
                            int want = (int) Math.min(len, total - off);
                            byte[] buf = new byte[want];
                            raf.seek(off);
                            int n = raf.read(buf);
                            raf.close();
                            if (n < 0) n = 0;
                            byte[] slice = n == want ? buf : java.util.Arrays.copyOf(buf, n);
                            String b64 = android.util.Base64.encodeToString(slice, android.util.Base64.NO_WRAP);
                            int chunk = 60000;
                            for (int i = 0; i < b64.length(); i += chunk) {
                                netOut.write(("B64:" + b64.substring(i, Math.min(b64.length(), i + chunk)) + "\n").getBytes());
                                netOut.flush();
                            }
                            res.append("PULLEND ").append(off).append(" ").append(n).append(" ").append(total).append("\n");
                        } catch (Throwable t) {
                            res.append("PULLERR ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                        }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#BUS ")) {
                    String rest = firstLine.trim().substring(5).trim();
                    String[] tk = rest.split("\\s+");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String ceType = tk.length > 0 ? tk[0] : "cls.vehicle.body.cabin_climate.v1.ExecuteClimateCommand";
                    String hexPayload = tk.length > 1 ? tk[1] : "";
                    res.append("BUS type=").append(ceType).append(" payload=").append(hexPayload.length() / 2).append("B\n");
                    netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                    final CountDownLatch latch = new CountDownLatch(1);
                    final Object[] holder = new Object[1];
                    ServiceConnection conn = new ServiceConnection() {
                        public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
                        public void onServiceDisconnected(ComponentName n) { }
                    };
                    try {
                        Intent it = new Intent();
                        it.setComponent(new ComponentName("com.cls.service.servicebus", "com.cls.service.servicebus.BusService"));
                        bindService(it, conn, android.content.Context.BIND_AUTO_CREATE);
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                        IBinder bus = (IBinder) holder[0];
                        if (bus == null) { res.append("no binder\n"); }
                        else {
                            String desc = null;
                            try { desc = bus.getInterfaceDescriptor(); } catch (Throwable t) { }
                            res.append("desc=").append(desc).append("\n");
                            android.os.Binder token = new android.os.Binder();
                            for (int c = 1; c <= 6; c++) {
                                android.os.Parcel d = android.os.Parcel.obtain();
                                android.os.Parcel r = android.os.Parcel.obtain();
                                try {
                                    if (desc != null) d.writeInterfaceToken(desc);
                                    d.writeString("com.vox.castsink");
                                    d.writeStrongBinder(token);
                                    d.writeStrongBinder(null);
                                    boolean tr = bus.transact(c, d, r, 0);
                                    byte[] bb = null;
                                    try { bb = r.marshall(); } catch (Throwable t) { }
                                    res.append("  REG c=").append(c).append(" ret=").append(tr).append(" avail=").append(r.dataAvail());
                                    if (bb != null && bb.length > 0) res.append(" hex=").append(hexOf(bb, 0, Math.min(bb.length, 70)));
                                    res.append("\n");
                                } catch (Throwable t) {
                                    res.append("  REG c=").append(c).append(" EX ").append(t.getClass().getSimpleName()).append("\n");
                                } finally { d.recycle(); r.recycle(); netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0); }
                            }
                            byte[] ce = buildCloudEvent(ceType, hexPayload);
                            for (int c = 1; c <= 6; c++) {
                                android.os.Parcel d = android.os.Parcel.obtain();
                                android.os.Parcel r = android.os.Parcel.obtain();
                                try {
                                    if (desc != null) d.writeInterfaceToken(desc);
                                    d.writeByteArray(ce);
                                    d.writeStrongBinder(token);
                                    boolean tr = bus.transact(c, d, r, 0);
                                    byte[] bb = null;
                                    try { bb = r.marshall(); } catch (Throwable t) { }
                                    res.append("  SEND c=").append(c).append(" ret=").append(tr).append(" avail=").append(r.dataAvail());
                                    if (bb != null && bb.length > 0) res.append(" hex=").append(hexOf(bb, 0, Math.min(bb.length, 90)));
                                    res.append("\n");
                                } catch (Throwable t) {
                                    res.append("  SEND c=").append(c).append(" EX ").append(t.getClass().getSimpleName()).append("\n");
                                } finally { d.recycle(); r.recycle(); netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0); }
                            }
                        }
                    } catch (Throwable t) {
                        res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                    } finally {
                        try { unbindService(conn); } catch (Throwable t) { }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#BUS2 ")) {
                    String rest = firstLine.trim().substring(6).trim();
                    String[] tk = rest.split("\\s+");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    int regCode = tk.length > 0 ? Integer.parseInt(tk[0]) : 6;
                    int sendCode = tk.length > 1 ? Integer.parseInt(tk[1]) : 3;
                    String ceType = tk.length > 2 ? tk[2] : "cls.vehicle.body.cabin_climate.v1.ExecuteClimateCommand";
                    String hexPayload = tk.length > 3 ? tk[3] : "";
                    final CountDownLatch latch = new CountDownLatch(1);
                    final Object[] holder = new Object[1];
                    ServiceConnection conn = new ServiceConnection() {
                        public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
                        public void onServiceDisconnected(ComponentName n) { }
                    };
                    try {
                        Intent it = new Intent();
                        it.setComponent(new ComponentName("com.cls.service.servicebus", "com.cls.service.servicebus.BusService"));
                        bindService(it, conn, android.content.Context.BIND_AUTO_CREATE);
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                        IBinder bus = (IBinder) holder[0];
                        if (bus == null) {
                            res.append("no binder\n");
                        } else {
                            String desc = null;
                            try { desc = bus.getInterfaceDescriptor(); } catch (Throwable t) { }
                            android.os.Binder token = new android.os.Binder();
                            EvListener listener = new EvListener();
                            res.append("REG code=").append(regCode).append("\n");
                            android.os.Parcel d = android.os.Parcel.obtain();
                            android.os.Parcel r = android.os.Parcel.obtain();
                            try {
                                if (desc != null) d.writeInterfaceToken(desc);
                                d.writeString("com.vox.castsink");
                                d.writeStrongBinder(token);
                                d.writeStrongBinder(listener);
                                boolean tr = bus.transact(regCode, d, r, 0);
                                byte[] bb = null;
                                try { bb = r.marshall(); } catch (Throwable t) { }
                                res.append("  REG ret=").append(tr).append(" avail=").append(r.dataAvail());
                                if (bb != null && bb.length > 0) res.append(" hex=").append(hexOf(bb, 0, Math.min(bb.length, 90)));
                                res.append("\n");
                            } catch (Throwable t) {
                                res.append("  REG EX ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                            } finally { d.recycle(); r.recycle(); }
                            netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                            Thread.sleep(600);
                            if (sendCode > 0) {
                                byte[] ce = buildCloudEvent(ceType, hexPayload);
                                res.append("SEND code=").append(sendCode).append(" bytes=").append(ce.length).append("\n");
                                android.os.Parcel d2 = android.os.Parcel.obtain();
                                android.os.Parcel r2 = android.os.Parcel.obtain();
                                try {
                                    if (desc != null) d2.writeInterfaceToken(desc);
                                    d2.writeByteArray(ce);
                                    d2.writeStrongBinder(token);
                                    boolean tr2 = bus.transact(sendCode, d2, r2, 0);
                                    byte[] bb2 = null;
                                    try { bb2 = r2.marshall(); } catch (Throwable t) { }
                                    res.append("  SEND ret=").append(tr2).append(" avail=").append(r2.dataAvail());
                                    if (bb2 != null && bb2.length > 0) res.append(" hex=").append(hexOf(bb2, 0, Math.min(bb2.length, 110)));
                                    res.append("\n");
                                } catch (Throwable t) {
                                    res.append("  SEND EX ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                                } finally { d2.recycle(); r2.recycle(); }
                            }
                        }
                    } catch (Throwable t) {
                        res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                    } finally {
                        try { unbindService(conn); } catch (Throwable t) { }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#BUS3 ")) {
                    String rest = firstLine.trim().substring(6).trim();
                    String[] tk = rest.split("\\s+");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    String source = tk.length > 0 ? tk[0] : "cls:/body.cabin_climate/1/zone.row1_left";
                    String ceType = tk.length > 1 ? tk[1] : "ClimateCommand";
                    String hexPayload = tk.length > 2 ? tk[2] : "";
                    final CountDownLatch latch = new CountDownLatch(1);
                    final Object[] holder = new Object[1];
                    ServiceConnection conn = new ServiceConnection() {
                        public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
                        public void onServiceDisconnected(ComponentName n) { }
                    };
                    try {
                        Intent it = new Intent();
                        it.setComponent(new ComponentName("com.cls.service.servicebus", "com.cls.service.servicebus.BusService"));
                        bindService(it, conn, android.content.Context.BIND_AUTO_CREATE);
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                        IBinder bus = (IBinder) holder[0];
                        if (bus == null) {
                            res.append("no binder\n");
                        } else {
                            String desc = null;
                            try { desc = bus.getInterfaceDescriptor(); } catch (Throwable t) { }
                            android.os.Binder token = new android.os.Binder();
                            EvListener listener = new EvListener();
                            String clientId = "com.vox.castsink." + (System.currentTimeMillis() % 100000);
                            res.append("client=").append(clientId).append("\n");
                            android.os.Parcel d = android.os.Parcel.obtain();
                            android.os.Parcel r = android.os.Parcel.obtain();
                            try {
                                if (desc != null) d.writeInterfaceToken(desc);
                                d.writeString(clientId);
                                d.writeStrongBinder(token);
                                d.writeStrongBinder(listener);
                                boolean tr = bus.transact(1, d, r, 0);
                                byte[] bb = null;
                                try { bb = r.marshall(); } catch (Throwable t) { }
                                res.append("REG ret=").append(tr).append(" avail=").append(r.dataAvail());
                                if (bb != null && bb.length > 0) res.append(" hex=").append(hexOf(bb, 0, Math.min(bb.length, 100)));
                                res.append("\n");
                            } catch (Throwable t) {
                                res.append("REG EX ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                            } finally { d.recycle(); r.recycle(); }
                            netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                            Thread.sleep(800);
                            byte[] ce = buildCloudEvent2(source, ceType, hexPayload);
                            res.append("SEND source=").append(source).append(" type=").append(ceType).append(" bytes=").append(ce.length).append("\n");
                            android.os.Parcel d2 = android.os.Parcel.obtain();
                            android.os.Parcel r2 = android.os.Parcel.obtain();
                            try {
                                if (desc != null) d2.writeInterfaceToken(desc);
                                d2.writeByteArray(ce);
                                d2.writeStrongBinder(token);
                                boolean tr2 = bus.transact(3, d2, r2, 0);
                                byte[] bb2 = null;
                                try { bb2 = r2.marshall(); } catch (Throwable t) { }
                                res.append("SEND ret=").append(tr2).append(" avail=").append(r2.dataAvail());
                                if (bb2 != null && bb2.length > 0) res.append(" hex=").append(hexOf(bb2, 0, Math.min(bb2.length, 120)));
                                res.append("\n");
                            } catch (Throwable t) {
                                res.append("SEND EX ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                            } finally { d2.recycle(); r2.recycle(); }
                        }
                    } catch (Throwable t) {
                        res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                    } finally {
                        try { unbindService(conn); } catch (Throwable t) { }
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#HTTP ")) {
                    String rest = firstLine.trim().substring(6).trim();
                    String[] tk = rest.split("\\s+");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    if (tk.length < 1 || tk[0].length() == 0) {
                        res.append("Usage: #HTTP <path> [port]\n");
                    } else {
                        final String path = tk[0];
                        int port = 8901;
                        if (tk.length > 1) { try { port = Integer.parseInt(tk[1]); } catch (Throwable t) { } }
                        final int fport = port;
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    java.io.File f = new java.io.File(path);
                                    long len = f.length();
                                    ServerSocket ss = new ServerSocket(fport);
                                    logLine("HTTP serving " + path + " " + len + "B port=" + fport);
                                    while (true) {
                                        Socket s = ss.accept();
                                        try {
                                            java.io.InputStream in = s.getInputStream();
                                            byte[] hdr = new byte[2048];
                                            in.read(hdr);
                                            java.io.OutputStream os = s.getOutputStream();
                                            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + len + "\r\nConnection: close\r\n\r\n").getBytes());
                                            java.io.FileInputStream fis = new java.io.FileInputStream(f);
                                            byte[] b2 = new byte[65536];
                                            int n;
                                            while ((n = fis.read(b2)) > 0) os.write(b2, 0, n);
                                            fis.close();
                                            os.flush();
                                            s.close();
                                            logLine("HTTP sent " + len + "B");
                                        } catch (Throwable ex) { logLine("HTTP conn err " + ex.getClass().getSimpleName()); }
                                    }
                                } catch (Throwable t) {
                                    logLine("HTTP ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
                                }
                            }
                        });
                        t.setDaemon(true);
                        t.start();
                        res.append("HTTP serving ").append(path).append(" port=").append(fport).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#BINDID ")) {
                    String rest = firstLine.trim().substring(8).trim();
                    String[] tk = rest.split("\\s+");
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    if (tk.length < 2) {
                        res.append("Usage: #BINDID <pkg>/<cls> <id> [childCode]\n");
                    } else {
                        String comp = tk[0];
                        int slash = comp.indexOf('/');
                        final String pkg = slash > 0 ? comp.substring(0, slash) : comp;
                        final String cls = slash > 0 ? comp.substring(slash + 1) : comp;
                        final int id;
                        int childCode = 0;
                        int tmpId = 0;
                        try { tmpId = Integer.parseInt(tk[1]); } catch (Throwable t) { }
                        id = tmpId;
                        if (tk.length > 2) { try { childCode = Integer.parseInt(tk[2]); } catch (Throwable t) { } }
                        final int fchild = childCode;
                        final CountDownLatch latch = new CountDownLatch(1);
                        final Object[] holder = new Object[1];
                        ServiceConnection conn = new ServiceConnection() {
                            public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
                            public void onServiceDisconnected(ComponentName n) { }
                        };
                        try {
                            Intent it = new Intent();
                            it.setComponent(new ComponentName(pkg, cls));
                            bindService(it, conn, android.content.Context.BIND_AUTO_CREATE);
                            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                            IBinder b = (IBinder) holder[0];
                            if (b == null) {
                                res.append("no binder\n");
                            } else {
                                String desc = null;
                                try { desc = b.getInterfaceDescriptor(); } catch (Throwable t) { }
                                res.append("pool desc=").append(desc).append("\n");
                                android.os.Parcel d = android.os.Parcel.obtain();
                                android.os.Parcel r = android.os.Parcel.obtain();
                                IBinder child = null;
                                try {
                                    if (desc != null) d.writeInterfaceToken(desc);
                                    d.writeInt(id);
                                    boolean tr = b.transact(1, d, r, 0);
                                    int exc = -1;
                                    try { exc = r.readInt(); } catch (Throwable t) { }
                                    if (exc == 0) { try { child = r.readStrongBinder(); } catch (Throwable t) { } }
                                    res.append("query id=").append(id).append(" ret=").append(tr).append(" exc=").append(exc).append("\n");
                                } catch (Throwable t) {
                                    res.append("query EX ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                                } finally { d.recycle(); r.recycle(); }
                                netOut.write(res.toString().getBytes()); netOut.flush(); res.setLength(0);
                                if (child == null) {
                                    res.append("child=null\n");
                                } else {
                                    String cd = null;
                                    try { cd = child.getInterfaceDescriptor(); } catch (Throwable t) { }
                                    res.append("child desc=").append(cd).append("\n");
                                    if (fchild > 0) {
                                        android.os.Parcel d2 = android.os.Parcel.obtain();
                                        android.os.Parcel r2 = android.os.Parcel.obtain();
                                        try {
                                            if (cd != null) d2.writeInterfaceToken(cd);
                                            boolean tr2 = child.transact(fchild, d2, r2, 0);
                                            byte[] bb = null;
                                            try { bb = r2.marshall(); } catch (Throwable t) { }
                                            res.append("child code=").append(fchild).append(" ret=").append(tr2).append(" avail=").append(r2.dataAvail());
                                            if (bb != null && bb.length > 0) res.append(" hex=").append(hexOf(bb, 0, Math.min(bb.length, 90)));
                                            res.append("\n");
                                        } catch (Throwable t) {
                                            res.append("child EX ").append(t.getClass().getSimpleName()).append("\n");
                                        } finally { d2.recycle(); r2.recycle(); }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
                        } finally {
                            try { unbindService(conn); } catch (Throwable t) { }
                        }
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
                                params.setAppPackageName("com.vox.castsink");
                                
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
                                params.setAppPackageName("com.vox.castsink");
                                
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

                } else if (cmd.startsWith("#BRIDGECALL ")) {
                    String rest = firstLine.trim().substring(11).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        String[] tk = rest.split(" ", 3);
                        final String fcls = tk[0];
                        final String fmth = tk[1];
                        String[] rawArgs = (tk.length > 2 && !tk[2].isEmpty()) ? tk[2].split("\\|", -1) : new String[0];
                        final Class[] types = new Class[rawArgs.length];
                        final Object[] params = new Object[rawArgs.length];
                        for (int i = 0; i < rawArgs.length; i++) { types[i] = String.class; params[i] = "~".equals(rawArgs[i]) ? null : rawArgs[i]; }
                        if (BRIDGE_BINDER == null) {
                            Intent svc = new Intent();
                            svc.setComponent(new ComponentName("com.baidu.che.codriver", "com.baidu.bridge.server.AIDLService"));
                            final java.util.concurrent.CountDownLatch bl = new java.util.concurrent.CountDownLatch(1);
                            ServiceConnection bconn = new ServiceConnection() {
                                public void onServiceConnected(ComponentName c, IBinder b) { BRIDGE_BINDER = b; bl.countDown(); }
                                public void onServiceDisconnected(ComponentName c) { }
                            };
                            bindService(svc, bconn, BIND_AUTO_CREATE);
                            bl.await(6, java.util.concurrent.TimeUnit.SECONDS);
                        }
                        if (BRIDGE_BINDER == null) {
                            res.append("bridge bind failed\n");
                        } else {
                            android.os.Parcel data = android.os.Parcel.obtain();
                            android.os.Parcel reply = android.os.Parcel.obtain();
                            data.writeInterfaceToken("com.baidu.bridge.ICall");
                            data.writeInt(1);
                            data.writeString(fcls);
                            data.writeString(fmth);
                            data.writeString(null);
                            data.writeInt(0);
                            data.writeInt(0);
                            data.writeSerializable(types);
                            data.writeSerializable(types);
                            data.writeSerializable(params);
                            BRIDGE_BINDER.transact(1, data, reply, 0);
                            reply.readException();
                            res.append("avail=").append(reply.dataAvail()).append("\n");
                            try {
                                byte[] rawReply = reply.marshall();
                                StringBuilder hx = new StringBuilder();
                                for (byte bb : rawReply) hx.append(String.format("%02x", bb));
                                java.io.FileOutputStream fo = new java.io.FileOutputStream("/sdcard/Download/bridge_last.txt");
                                fo.write(hx.toString().getBytes());
                                fo.close();
                                res.append("dumped ").append(rawReply.length).append(" bytes\n");
                            } catch (Throwable t) {
                                res.append("dump err=").append(truncate(t.toString(), 120)).append("\n");
                            }
                            try {
                                int startPos = reply.dataPosition();
                                int firstInt = reply.readInt();
                                if (firstInt != 1) reply.setDataPosition(startPos);
                                String rc = reply.readString();
                                String rm = reply.readString();
                                String cb = reply.readString();
                                int oh = reply.readInt();
                                int cm = reply.readInt();
                                res.append("ret cls=").append(rc).append(" m=").append(rm).append(" cb=").append(cb).append(" oh=").append(oh).append(" cm=").append(cm).append("\n");
                                Object pt = reply.readSerializable();
                                Object prt = reply.readSerializable();
                                Object pa = reply.readSerializable();
                                Object ra = reply.readSerializable();
                                String rs = "null";
                                if (ra instanceof Object[]) {
                                    Object[] arr = (Object[]) ra;
                                    StringBuilder sb = new StringBuilder("[");
                                    for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(", "); sb.append(String.valueOf(arr[i])); }
                                    sb.append("]");
                                    rs = sb.toString();
                                }
                                res.append("result=").append(rs).append("\n");
                            } catch (Throwable t) {
                                res.append("read err=").append(truncate(t.toString(), 160)).append("\n");
                            }
                            data.recycle(); reply.recycle();
                        }
                    } catch (Throwable e) {
                        res.append("Error: ").append(truncate(e.toString(), 300)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#DCSRAW ")) {
                    String rest = firstLine.trim().substring(8).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        boolean lastBinder = rest.contains("--lb");
                        rest = rest.replace("--lb", "").trim();
                        String[] tk = rest.split(" ");
                        String desc = tk[0];
                        int code = Integer.parseInt(tk[1]);
                        String argstr = tk.length > 2 ? tk[2] : "";
                        String[] vals = argstr.isEmpty() ? new String[0] : argstr.split("\\|", -1);
                        if (DCS_BINDER == null) {
                            res.append("no binder yet; run #DCSREG first\n");
                        } else {
                            android.os.Parcel data = android.os.Parcel.obtain();
                            android.os.Parcel reply = android.os.Parcel.obtain();
                            data.writeInterfaceToken(desc);
                            for (String v : vals) data.writeString("~".equals(v) ? null : v);
                            if (lastBinder) data.writeStrongBinder(DCS_LISTENER);
                            boolean ok = DCS_BINDER.transact(code, data, reply, 0);
                            res.append("transact=").append(ok).append(" avail=").append(reply.dataAvail());
                            try {
                                reply.readException();
                                String s = reply.readString();
                                res.append(" str=").append(s);
                            } catch (Throwable t) {
                                res.append(" EX=").append(truncate(t.toString(), 200));
                            }
                            res.append("\n");
                            data.recycle(); reply.recycle();
                        }
                    } catch (Throwable e) {
                        res.append("Error: ").append(truncate(e.toString(), 300)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#DCSLOG")) {
                    OutputStream netOut = client.getOutputStream();
                    netOut.write(DCS_LOG.toString().getBytes());
                    DCS_LOG.setLength(0);
                    netOut.flush();

                } else if (cmd.startsWith("#DCSREG ")) {
                    String rest = firstLine.trim().substring(8).trim();
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        String[] tk = rest.split(" ", 4);
                        String comp = tk[0];
                        final String desc = tk[1];
                        final int code = Integer.parseInt(tk[2]);
                        final String appId = tk.length > 3 ? tk[3] : "com.vox.castsink";
                        int slash = comp.indexOf('/');
                        Intent svc = new Intent();
                        svc.setComponent(new ComponentName(comp.substring(0, slash), comp.substring(slash + 1)));
                        if (DCS_LISTENER == null) {
                            DCS_LISTENER = new android.os.Binder() {
                                @Override
                                public boolean onTransact(int c, android.os.Parcel d, android.os.Parcel r, int f) {
                                    try {
                                        byte[] raw = d.marshall();
                                        StringBuilder h = new StringBuilder();
                                        for (byte bb : raw) h.append(String.format("%02x", bb));
                                        DCS_LOG.append("CB code=").append(c).append(" hex=").append(h).append("\n");
                                    } catch (Throwable t) {
                                        DCS_LOG.append("CB code=").append(c).append(" err=").append(t).append("\n");
                                    }
                                    return true;
                                }
                            };
                        }
                        if (DCS_CONN == null) {
                            DCS_CONN = new ServiceConnection() {
                                public void onServiceConnected(ComponentName c, IBinder b) {
                                    DCS_BINDER = b;
                                    try {
                                        android.os.Parcel data = android.os.Parcel.obtain();
                                        android.os.Parcel reply = android.os.Parcel.obtain();
                                        data.writeInterfaceToken(desc);
                                        data.writeString(appId);
                                        data.writeStrongBinder(DCS_LISTENER);
                                        b.transact(code, data, reply, 0);
                                        reply.readException();
                                        String s = null;
                                        try { s = reply.readString(); } catch (Throwable t) { }
                                        DCS_LOG.append("REG code=").append(code).append(" ret=").append(s).append("\n");
                                        data.recycle(); reply.recycle();
                                    } catch (Throwable t) {
                                        DCS_LOG.append("REG err=").append(t).append("\n");
                                    }
                                }
                                public void onServiceDisconnected(ComponentName c) { DCS_LOG.append("disconnected\n"); }
                            };
                        }
                        bindService(svc, DCS_CONN, BIND_AUTO_CREATE);
                        res.append("bind requested\n");
                    } catch (Throwable e) {
                        res.append("Error: ").append(truncate(e.toString(), 300)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#BINDSTR ")) {
                    String rest = firstLine.trim().substring(9).trim();
                    Log.i(TAG, "Command: BINDSTR " + rest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== BINDSTR: ").append(rest).append(" ===\n");
                    try {
                        String[] tk = rest.split(" ", 4);
                        if (tk.length < 4) {
                            res.append("Usage: #BINDSTR <pkg>/<cls> <descriptor> <code> <s1|s2|s3>\n");
                        } else {
                            String comp = tk[0];
                            int slash = comp.indexOf('/');
                            final String pkg = slash > 0 ? comp.substring(0, slash) : comp;
                            final String cls = slash > 0 ? comp.substring(slash + 1) : comp;
                            final String desc = tk[1];
                            final int code = Integer.parseInt(tk[2].trim());
                            String[] vals = tk[3].split("\\|", -1);
                            final java.util.ArrayList<String> args = new java.util.ArrayList<String>();
                            for (String v : vals) args.add("~".equals(v) ? null : v);
                            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                            final StringBuilder out = new StringBuilder();
                            Intent svc = new Intent();
                            svc.setComponent(new android.content.ComponentName(pkg, cls));
                            ServiceConnection conn = new ServiceConnection() {
                                public void onServiceConnected(android.content.ComponentName c, IBinder b) {
                                    try {
                                        android.os.Parcel data = android.os.Parcel.obtain();
                                        android.os.Parcel reply = android.os.Parcel.obtain();
                                        data.writeInterfaceToken(desc);
                                        for (String a : args) data.writeString(a);
                                        b.transact(code, data, reply, 0);
                                        reply.readException();
                                        int avail = reply.dataAvail();
                                        String s = null;
                                        try { s = reply.readString(); } catch (Throwable t) { }
                                        out.append("code=").append(code).append(" avail=").append(avail)
                                           .append(" str=").append(s).append("\n");
                                        data.recycle(); reply.recycle();
                                    } catch (Throwable t) {
                                        out.append("EX: ").append(truncate(t.toString(), 300)).append("\n");
                                    } finally { latch.countDown(); }
                                }
                                public void onServiceDisconnected(android.content.ComponentName c) { }
                            };
                            bindService(svc, conn, BIND_AUTO_CREATE);
                            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                            try { unbindService(conn); } catch (Throwable t) { }
                            res.append(out.length() == 0 ? "no callback (timeout)\n" : out.toString());
                        }
                    } catch (Throwable e) {
                        res.append("Error: ").append(truncate(e.toString(), 300)).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.startsWith("#BCAST ")) {
                    String rest = firstLine.trim().substring(7).trim();
                    Log.i(TAG, "Command: BCAST " + rest);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    res.append("=== BCAST: ").append(rest).append(" ===\n");
                    try {
                        String[] tk = rest.split("\\s+");
                        Intent intent = new Intent(tk[0]);
                        for (int i = 1; i < tk.length; i++) {
                            if (tk[i].equals("--es") && i + 2 < tk.length) { intent.putExtra(tk[i+1], tk[i+2]); i += 2; }
                            else if (tk[i].equals("--ez") && i + 2 < tk.length) { intent.putExtra(tk[i+1], Boolean.parseBoolean(tk[i+2])); i += 2; }
                            else if (tk[i].equals("--ei") && i + 2 < tk.length) { try { intent.putExtra(tk[i+1], Integer.parseInt(tk[i+2])); } catch (Throwable t) { } i += 2; }
                        }
                        sendBroadcast(intent);
                        res.append("  sent: ").append(intent).append("\n");
                    } catch (Exception e) {
                        res.append("  Error: ").append(truncate(e.toString(), 200)).append("\n");
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

                } else if (cmd.startsWith("#BUSPAUSE")) {
                    // v130: 运行时急停开关 — on: CarBus 拒绝一切外发，NavPush/ObdNet/Telemetry/NetState 只 sleep 不请求
                    String arg = firstLine.trim().length() > 9 ? firstLine.trim().substring(9).trim() : "";
                    boolean on = arg.equalsIgnoreCase("on");
                    com.vox.castsink.CarBus.INSTANCE.setPaused(on);
                    Log.i(TAG, "Command: BUSPAUSE " + arg);
                    OutputStream netOut = client.getOutputStream();
                    try {
                        netOut.write(("BUSPAUSE=" + (on ? "on" : "off") + "\n").getBytes());
                        netOut.flush();
                    } catch (IOException ignored) {}

                } else if (cmd.startsWith("#BINDSVC ")) {
                    // v130: binder 缓存复用 — 命中直接 transact，未命中走 bind→transact→入缓存（见 bindsvcShared）
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
                        String[] mainArgs = new String[parts.length - 2];
                        for (int i = 2; i < parts.length; i++) mainArgs[i-2] = parts[i];
                        try {
                            bindsvcShared(pkg, cls, mainArgs, chainPart, res);
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
                            final android.os.Binder carCallback = new android.os.Binder() {
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
                        dynPerms.add(new String[]{"BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_CONNECT"});
                        dynPerms.add(new String[]{"BLUETOOTH_SCAN", "android.permission.BLUETOOTH_SCAN"});
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

                } else if (cmd.startsWith("#PLAY ") || cmd.equals("#STOPAUDIO")) {
                    Log.i(TAG, "Command: " + cmd);
                    OutputStream netOut = client.getOutputStream();
                    StringBuilder res = new StringBuilder();
                    try {
                        if (cmd.equals("#STOPAUDIO")) {
                            stopAudio();
                            res.append("AUDIO STOPPED\n");
                        } else {
                            playAudio(firstLine.trim().substring(6).trim(), res);
                        }
                    } catch (Exception e) {
                        res.append("AUDIO ERROR: ").append(e.toString()).append("\n");
                    }
                    netOut.write(res.toString().getBytes());
                    netOut.flush();

                } else if (cmd.equals("#HYB")) {
                    OutputStream hOut = client.getOutputStream();
                    String hs = "HYB soc=" + VehicleState.INSTANCE.getSoc()
                        + " hybW=" + VehicleState.INSTANCE.getHybPowerW()
                        + " canW=" + VehicleState.INSTANCE.getCarHybW()
                        + " wh=" + VehicleState.INSTANCE.getHybWh()
                        + " trim=" + HybridPower.INSTANCE.getTrimW()
                        + " assist=" + HybridPower.INSTANCE.getThrottleW()
                        + " regen=" + HybridPower.INSTANCE.getRegenW()
                        + " speed=" + VehicleState.INSTANCE.getSpeed() + "\n";
                    hOut.write(hs.getBytes());
                    hOut.flush();

                } else if (cmd.startsWith("#MOTOR")) {
                    Log.i(TAG, "Command: " + cmd);
                    OutputStream mOut = client.getOutputStream();
                    StringBuilder mres = new StringBuilder();
                    String ma = cmd.length() > 6 ? cmd.substring(6).trim().toLowerCase(java.util.Locale.US) : "";
                    try {
                        if (ma.startsWith("off")) {
                            MotorSound.INSTANCE.stop();
                            mres.append("MOTOR OFF\n");
                        } else if (ma.startsWith("vol ")) {
                            MotorSound.INSTANCE.setGain(Float.parseFloat(ma.substring(4).trim()) / 100f);
                            mres.append("MOTOR VOL ").append(ma.substring(4).trim()).append("\n");
                        } else if (ma.startsWith("status")) {
                            mres.append("MOTOR ").append(MotorSound.INSTANCE.isOn() ? "ON" : "OFF").append("\n");
                        } else if (ma.startsWith("on")) {
                            if (!MotorSound.INSTANCE.isOn()) MotorSound.INSTANCE.start(ShellService.this);
                            mres.append("MOTOR ON\n");
                        } else {
                            if (MotorSound.INSTANCE.isOn()) { MotorSound.INSTANCE.stop(); mres.append("MOTOR OFF\n"); }
                            else { MotorSound.INSTANCE.start(ShellService.this); mres.append("MOTOR ON\n"); }
                        }
                    } catch (Exception me) {
                        mres.append("MOTOR ERROR: ").append(me.toString()).append("\n");
                    }
                    mOut.write(mres.toString().getBytes());
                    mOut.flush();

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
                        "  #STR16 <path|@pkg> <pattern> [max]  Scan apk dex/manifest/arsc strings\n" +
                        "  #APKOF <pkg>  Show APK path\n" +
                        "  #PERMCHK <perm>  Permission protection level + self grant\n" +
                        "  #PROPS    Probe CarPropertyManager (speed/hvac/lights/gear)\n" +
                        "  #BINDSVC <pkg>/<cls> [--code=N] [--ints=a,b] [--str=s] [--bytes=hex] [--binder]  Bind + transact\n" +
                        "  #BINSCAN <pkg>/<cls> [maxCode]  Bind + try transact codes 1..N\n" +
                        "  #LOCATE <svcName> [--child=N] [--ints=a,b] [--str=s]  PatacService locator -> sub-binder -> transact\n" +
                        "  #ADALISTEN [secs]  Register IAdasDataListener callback, dump received frames\n" +
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
                        "  #CLUSTER START [displayId]  Launch CastSink ClusterCastActivity on cluster display (am start --display)\n" +
                        "  #CLUSTER STOP  Close cluster dashboard (broadcast com.vox.castsink.FINISH_CLUSTER)\n" +
                        "  #CLUSTER PROBE  Diagnostics for #CLUSTER (uid, su, sdk, dumpsys readability)\n" +
                        "  #PLAY <path> [--vol 0-100]  Play an audio file via MediaPlayer (AudioFlinger)\n" +
                        "  #MOTOR on|off|vol 0-100  EV motor whine driven by live 48V power\n" +
                        "  #HYB      Dump 48V power internals (soc/hybW/canW/Wh)\n" +
                        "  #STOPAUDIO  Stop current playback\n" +
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
        try { clearBinderCache(); } catch (Throwable ignored) {}   // v130: 服务销毁时清理 binder 缓存
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
                    + " -f 0x10000000 -n com.vox.castsink/.ClusterCastActivity";
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
            String out = runCapture("am broadcast -a com.vox.castsink.FINISH_CLUSTER");
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

    public static class AdasCb extends android.os.Binder {
        public final java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.List<String> samples = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
            hits.incrementAndGet();
            try { data.enforceInterface("com.gm.cn.adasservice.IAdasDataListener"); } catch (Throwable t) { }
            int avail = data.dataAvail();
            byte[] b = null;
            try { b = data.marshall(); } catch (Throwable t) { }
            String line = System.currentTimeMillis() + " code=" + code + " avail=" + avail
                    + " hex=" + (b == null ? "null" : hexOf(b, 0, Math.min(b.length, 160)));
            if (samples.size() < 8) samples.add(line);
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream("/sdcard/Download/adu_cb.txt", true);
                fos.write((line + "\n").getBytes());
                fos.close();
            } catch (Throwable t) { }
            if (reply != null) { try { reply.writeNoException(); } catch (Throwable t) { } }
            return true;
        }
    }

    private static AdasCb sCb;
    private static ServiceConnection sAdasConn;

    public static class PropCb extends android.os.Binder {
        public final java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
            hits.incrementAndGet();
            byte[] b = null;
            int first = -999;
            try { first = data.readInt(); } catch (Throwable t) { }
            try { b = data.marshall(); } catch (Throwable t) { }
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream("/sdcard/Download/adu_prop.txt", true);
                fos.write((System.currentTimeMillis() + " PROP cbcode=" + code + " first=" + first
                        + " hex=" + (b == null ? "null" : hexOf(b, 0, Math.min(b.length, 180))) + "\n").getBytes());
                fos.close();
            } catch (Throwable t) { }
            if (reply != null) { try { reply.writeNoException(); } catch (Throwable t) { } }
            return true;
        }
    }

    private static void adasPropBg(final android.content.Context ctx, final int secs) {
        try {
            final PropCb cb = new PropCb();
            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] holder = new Object[1];
            ServiceConnection conn = new ServiceConnection() {
                public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
                public void onServiceDisconnected(ComponentName n) { }
            };
            Intent it = new Intent();
            it.setComponent(new ComponentName("com.gm.cn.adasservice", "com.gm.cn.adasservice.AdasService"));
            boolean ok = ctx.bindService(it, conn, android.content.Context.BIND_AUTO_CREATE);
            logLine("PROP bind=" + ok);
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
            IBinder b = (IBinder) holder[0];
            if (b == null) { logLine("PROP no binder"); return; }
            String desc = null;
            try { desc = b.getInterfaceDescriptor(); } catch (Throwable t) { }
            logLine("PROP descriptor=" + desc);
            for (int c = 1; c <= 17; c++) {
                android.os.Parcel d = android.os.Parcel.obtain();
                android.os.Parcel r = android.os.Parcel.obtain();
                try {
                    if (desc != null) d.writeInterfaceToken(desc);
                    d.writeString("com.vox.castsink");
                    d.writeStrongBinder(cb);
                    boolean tr = b.transact(c, d, r, 0);
                    logLine("PROP reg c=" + c + " ret=" + tr + " avail=" + r.dataAvail());
                } catch (Throwable t) {
                    logLine("PROP reg c=" + c + " EX " + t.getClass().getSimpleName());
                } finally { d.recycle(); r.recycle(); }
            }
            logLine("PROP listening " + secs + "s");
            Thread.sleep(secs * 1000L);
            logLine("PROP done hits=" + cb.hits.get());
        } catch (Throwable t) {
            logLine("PROP ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void logLine(String s) {
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream("/sdcard/Download/adu_bg.log", true);
            fos.write((System.currentTimeMillis() + " " + s + "\n").getBytes());
            fos.close();
        } catch (Throwable t) { }
    }

    private static void adasListenBg(final android.content.Context ctx, final int secs) {
        try {
            final AdasCb cb = new AdasCb();
            sCb = cb;
            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] holder = new Object[1];
            ServiceConnection conn = new ServiceConnection() {
                public void onServiceConnected(ComponentName n, IBinder b) { holder[0] = b; latch.countDown(); }
                public void onServiceDisconnected(ComponentName n) { }
            };
            sAdasConn = conn;
            Intent it = new Intent();
            it.setComponent(new ComponentName("com.gm.cn.adasservice", "com.gm.cn.adasservice.AdasService"));
            boolean ok = ctx.bindService(it, conn, android.content.Context.BIND_AUTO_CREATE);
            logLine("BG bind=" + ok);
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
            IBinder b = (IBinder) holder[0];
            if (b == null) { logLine("BG no binder"); return; }
            String desc = null;
            try { desc = b.getInterfaceDescriptor(); } catch (Throwable t) { }
            logLine("BG descriptor=" + desc);
            for (int c = 1; c <= 17; c++) {
                android.os.Parcel d = android.os.Parcel.obtain();
                android.os.Parcel r = android.os.Parcel.obtain();
                try {
                    if (desc != null) d.writeInterfaceToken(desc);
                    d.writeStrongBinder(cb);
                    boolean tr = b.transact(c, d, r, 0);
                    logLine("BG reg c=" + c + " ret=" + tr + " avail=" + r.dataAvail());
                } catch (Throwable t) {
                    logLine("BG reg c=" + c + " EX " + t.getClass().getSimpleName());
                } finally {
                    d.recycle();
                    r.recycle();
                }
            }
            logLine("BG listening " + secs + "s");
            Thread.sleep(secs * 1000L);
        } catch (Throwable t) {
            logLine("BG ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try { if (sAdasConn != null) ctx.unbindService(sAdasConn); } catch (Throwable t) { }
            sAdasConn = null;
        }
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 32 && c < 127 ? c : '.');
        }
        return sb.toString();
    }

    private static void scanStream(java.io.InputStream in, String pat, java.util.LinkedHashSet<String> out, int max) {
        byte[] buf = new byte[1 << 20];
        byte[] tail = new byte[0];
        String lp = pat.toLowerCase();
        try {
            int n;
            while (out.size() < max && (n = in.read(buf)) > 0) {
                byte[] chunk = new byte[tail.length + n];
                System.arraycopy(tail, 0, chunk, 0, tail.length);
                System.arraycopy(buf, 0, chunk, tail.length, n);
                for (String enc : new String[]{"UTF-8", "UTF-16LE"}) {
                    try {
                        String s = new String(chunk, enc);
                        String ls = s.toLowerCase();
                        int idx = 0;
                        while (out.size() < max) {
                            int i2 = ls.indexOf(lp, idx);
                            if (i2 < 0) break;
                            int a = Math.max(0, i2 - 40);
                            int z = Math.min(s.length(), i2 + pat.length() + 40);
                            out.add(clean(s.substring(a, z)));
                            idx = i2 + lp.length();
                        }
                    } catch (Throwable t) { }
                }
                int keep = Math.min(256, chunk.length);
                tail = new byte[keep];
                System.arraycopy(chunk, chunk.length - keep, tail, 0, keep);
            }
        } catch (Throwable t) {
        } finally {
            try { in.close(); } catch (Throwable t) { }
        }
    }

    public static class EvListener extends android.os.Binder {
        @Override
        public String getInterfaceDescriptor() {
            return "com.cls.servicebus.IEventListener";
        }
        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
            if (reply != null) { try { reply.writeNoException(); } catch (Throwable t) { } }
            return true;
        }
    }

    private static void pbVarint(java.io.ByteArrayOutputStream b, long v) {
        while (true) {
            long x = v & 0x7f;
            v >>>= 7;
            if (v != 0) b.write((int) (x | 0x80));
            else { b.write((int) x); break; }
        }
    }

    private static void pbTag(java.io.ByteArrayOutputStream b, int field, int wt) {
        pbVarint(b, (field << 3) | wt);
    }

    private static void pbStr(java.io.ByteArrayOutputStream b, int field, String s) {
        try {
            byte[] d = s.getBytes("UTF-8");
            pbTag(b, field, 2);
            pbVarint(b, d.length);
            b.write(d);
        } catch (Throwable t) { }
    }

    private static void pbBytes(java.io.ByteArrayOutputStream b, int field, byte[] d) {
        pbTag(b, field, 2);
        pbVarint(b, d.length);
        try { b.write(d); } catch (Throwable t) { }
    }

    private static byte[] buildCloudEvent2(String source, String type, String hexPayload) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        pbStr(b, 1, "vox-" + System.currentTimeMillis());
        pbStr(b, 2, source);
        pbStr(b, 3, "1.0");
        pbStr(b, 4, type);
        if (hexPayload != null && hexPayload.length() >= 2) {
            byte[] p = new byte[hexPayload.length() / 2];
            for (int i = 0; i < p.length; i++) {
                try { p[i] = (byte) Integer.parseInt(hexPayload.substring(i * 2, i * 2 + 2), 16); } catch (Throwable t) { }
            }
            pbBytes(b, 5, p);
        }
        pbStr(b, 8, "application/protobuf");
        return b.toByteArray();
    }

    private static byte[] buildCloudEvent(String type, String hexPayload) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        pbStr(b, 1, "vox-" + System.currentTimeMillis());
        pbStr(b, 2, "com.vox.castsink");
        pbStr(b, 3, "1.0");
        pbStr(b, 4, type);
        if (hexPayload != null && hexPayload.length() >= 2) {
            byte[] p = new byte[hexPayload.length() / 2];
            for (int i = 0; i < p.length; i++) {
                try { p[i] = (byte) Integer.parseInt(hexPayload.substring(i * 2, i * 2 + 2), 16); } catch (Throwable t) { }
            }
            pbBytes(b, 5, p);
        }
        pbStr(b, 8, "application/protobuf");
        return b.toByteArray();
    }

    private static String hexOf(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < b.length && i < off + len; i++) {
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }

    private static byte[] readAll(java.io.InputStream in, int cap) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n, total = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total >= cap) break;
            }
        } finally {
            try { in.close(); } catch (Throwable t) { }
        }
        return bos.toByteArray();
    }

    private static void scanBuf(byte[] b, String pat, java.util.LinkedHashSet<String> out, int max) {
        if (b == null || b.length == 0) return;
        // 先 UTF-8，命中后不再跑 UTF-16（7MB dex 双解码会把 app 撑到 OOM 被杀）
        String[] encs0 = {"UTF-8", "UTF-16LE"};
        String[] encs = encs0;
        String lp = pat.toLowerCase();
        for (String enc : encs) {
            try {
                String s = new String(b, enc);
                String ls = s.toLowerCase();
                int idx = 0;
                while (out.size() < max) {
                    int i2 = ls.indexOf(lp, idx);
                    if (i2 < 0) break;
                    int a = Math.max(0, i2 - 50);
                    int z = Math.min(s.length(), i2 + pat.length() + 50);
                    String ctx = s.substring(a, z).replace('\n', ' ').replace('\r', ' ');
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < ctx.length(); k++) {
                        char ch = ctx.charAt(k);
                        sb.append(ch >= 32 && ch < 127 ? ch : '.');
                    }
                    out.add(sb.toString());
                    idx = i2 + lp.length();
                }
            } catch (Throwable t) { }
        }
    }

    private static String fmtVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Object[]) {
            StringBuilder sb = new StringBuilder("[");
            Object[] a = (Object[]) v;
            for (int i = 0; i < a.length && i < 8; i++) { if (i > 0) sb.append(","); sb.append(a[i]); }
            return sb.append("]").toString();
        }
        return String.valueOf(v);
    }

    private static String carAllProbe(android.content.Context ctx) {
        StringBuilder res = new StringBuilder("=== CAR ALL PROPS ===\n");
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            Object inst = null;
            for (java.lang.reflect.Method m : carCls.getMethods()) {
                if (!m.getName().equals("createCar")) continue;
                try { inst = m.getParameterTypes().length == 1 ? m.invoke(null, ctx) : m.invoke(null, ctx, null); break; } catch (Throwable t) { }
            }
            if (inst == null) { res.append("createCar=false\n"); return res.toString(); }
            Object mgr = carCls.getMethod("getCarManager", String.class).invoke(inst, "property");
            if (mgr == null) { res.append("no manager\n"); return res.toString(); }
            java.util.List<?> cfgs = (java.util.List<?>) mgr.getClass().getMethod("getPropertyList").invoke(mgr);
            java.lang.reflect.Method gp = null;
            for (java.lang.reflect.Method m : mgr.getClass().getMethods()) {
                if (m.getName().equals("getProperty") && m.getParameterTypes().length == 2) { gp = m; break; }
            }
            // name lookup
            java.util.HashMap<Integer,String> names = new java.util.HashMap<Integer,String>();
            try {
                Class<?> vpi = Class.forName("android.car.VehiclePropertyIds");
                for (java.lang.reflect.Field f : vpi.getFields()) {
                    if (f.getType() == int.class) { try { names.put(f.getInt(null), f.getName()); } catch (Throwable t) { } }
                }
            } catch (Throwable t) { }
            for (Object cfg : cfgs) {
                int pid = -1;
                try { pid = ((Integer) cfg.getClass().getMethod("getPropertyId").invoke(cfg)).intValue(); } catch (Throwable t) { }
                int area = 0;
                try { int[] as = (int[]) cfg.getClass().getMethod("getAreaIds").invoke(cfg); if (as != null && as.length > 0) area = as[0]; } catch (Throwable t) { }
                String nm = names.containsKey(pid) ? names.get(pid) : "?";
                String line = "  0x" + Integer.toHexString(pid) + " " + nm + " area=" + area + " -> ";
                try {
                    Object pv = gp.invoke(mgr, pid, area);
                    Object val = pv == null ? null : pv.getClass().getMethod("getValue").invoke(pv);
                    res.append(line).append(fmtVal(val)).append("\n");
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    res.append(line).append("! ").append(c.getClass().getSimpleName()).append(": ").append(String.valueOf(c.getMessage())).append("\n");
                }
            }
        } catch (Throwable t) {
            res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage())).append("\n");
        }
        return res.toString();
    }

    private static String carPropsProbe(android.content.Context ctx) {
        StringBuilder res = new StringBuilder("=== CAR PROPS ===\n");
        Object inst = null, mgr = null;
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            for (java.lang.reflect.Method m : carCls.getMethods()) {
                if (!m.getName().equals("createCar")) continue;
                try {
                    inst = m.getParameterTypes().length == 1 ? m.invoke(null, ctx) : m.invoke(null, ctx, null);
                    break;
                } catch (Throwable t) { }
            }
            res.append("createCar=").append(inst != null).append("\n");
            if (inst == null) return res.toString();
            mgr = carCls.getMethod("getCarManager", String.class).invoke(inst, "property");
            res.append("propertyManager=").append(mgr != null).append("\n");
            if (mgr == null) return res.toString();
        } catch (Throwable t) {
            res.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage())).append("\n");
            return res.toString();
        }
        try {
            java.lang.reflect.Method gl = mgr.getClass().getMethod("getPropertyList");
            Object cfgs = gl.invoke(mgr);
            if (cfgs instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>) cfgs;
                res.append("configs=").append(l.size()).append("\n");
                int c = 0;
                for (Object cfg : l) {
                    if (c++ >= 150) { res.append("  ...\n"); break; }
                    int id = -1;
                    try { id = ((Integer) cfg.getClass().getMethod("getPropertyId").invoke(cfg)).intValue(); } catch (Throwable t) { }
                    res.append("  cfg 0x").append(Integer.toHexString(id)).append(" ").append(id).append("\n");
                }
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            res.append("getPropertyList ! ").append(c.getClass().getSimpleName()).append(": ").append(String.valueOf(c.getMessage())).append("\n");
        }
        String[] names = {"PERF_VEHICLE_SPEED","PERF_ODOMETER","GEAR","ENGINE_RPM","ENGINE_COOLANT_TEMP",
            "HVAC_TEMPERATURE_SET","HVAC_FAN_SPEED","HVAC_AC_ON","HVAC_POWER_ON","HVAC_FAN_DIRECTION",
            "HVAC_DEFROSTER","HVAC_AUTO_ON","HVAC_SEAT_TEMPERATURE","HVAC_TEMPERATURE_DISPLAY_UNITS",
            "DOOR_POS","DOOR_MOVE","SEAT_OCCUPANCY","TURN_SIGNAL_STATE","HEADLIGHTS_STATE",
            "HIGH_BEAM_LIGHTS_STATE","PARKING_BRAKE_ON","EV_BATTERY_LEVEL","FUEL_LEVEL",
            "IGNITION_STATE","WINDOW_POS","CURTAIN_POS","INFO_DRIVER_SEAT"};
        java.lang.reflect.Method gp = null;
        for (java.lang.reflect.Method m : mgr.getClass().getMethods()) {
            if (m.getName().equals("getProperty") && m.getParameterTypes().length == 2) { gp = m; break; }
        }
        if (gp == null) {
            res.append("no getProperty(int,int)\n");
            return res.toString();
        }
        for (String nm : names) {
            int id;
            try {
                id = Class.forName("android.car.VehiclePropertyIds").getField(nm).getInt(null);
            } catch (Throwable t) { continue; }
            try {
                Object pv = gp.invoke(mgr, id, 0);
                Object val = pv == null ? null : pv.getClass().getMethod("getValue").invoke(pv);
                res.append("  ").append(nm).append(" = ").append(fmtVal(val)).append("\n");
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                res.append("  ").append(nm).append(" ! ").append(c.getClass().getSimpleName()).append(": ").append(String.valueOf(c.getMessage())).append("\n");
            }
        }
        return res.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
