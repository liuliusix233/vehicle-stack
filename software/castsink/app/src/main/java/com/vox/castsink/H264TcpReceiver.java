package com.vox.castsink;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import android.util.Log;

/**
 * TCP sink：4 字节大端长度前缀 + Annex-B NAL 载荷，逐帧上抛。
 * 对标凯迪拉克 libcarlink_cast_sink.so 的 RTP→AccessUnit 组装，
 * M1 用简化的 length-prefixed TCP framing 替代 RTSP/RTP 协商。
 *
 * 纯 Java 静态解析方法（readFrame/splitLengthPrefixed/firstNalType/nalTypeMask）
 * 不依赖 Android runtime，可在桌面 JVM 直接跑单测（见 NalParseTest）。
 */
public final class H264TcpReceiver {

    private static final String TAG = "H264TcpReceiver";
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    public interface FrameListener {
        /** 一个长度前缀帧（通常为一个 AccessUnit 的 Annex-B 字节流）。 */
        void onAccessUnit(byte[] annexBAccessUnit);
    }

    private final int port;
    private final FrameListener listener;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread thread;

    public H264TcpReceiver(int port, FrameListener listener) {
        this.port = port;
        this.listener = listener;
    }

    public int getPort() {
        return port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        thread = new Thread(this::acceptLoop, "H264TcpReceiver");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            try (Socket s = serverSocket.accept()) {
                Log.i(TAG, "client connected: " + s.getRemoteSocketAddress());
                onDataClient(s);
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept/read error", e);
            }
        }
    }

    private void onDataClient(Socket s) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 256 * 1024));
        long n = 0;
        while (running) {
            byte[] au = readFrame(in);
            if (au == null) break; // EOF
            n++;
            listener.onAccessUnit(au);
        }
        Log.i(TAG, "client closed after " + n + " frames");
    }

    /** 读一帧：4 字节大端长度 + 载荷；EOF 返回 null。 */
    public static byte[] readFrame(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (IOException e) {
            return null;
        }
        if (len <= 0 || len > MAX_FRAME_BYTES) {
            throw new IOException("bad frame length " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }

    /** 把含多个长度前缀帧的完整 buffer 拆成帧列表（单测用）。 */
    public static List<byte[]> splitLengthPrefixed(byte[] data) throws IOException {
        List<byte[]> out = new ArrayList<>();
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
        byte[] f;
        while ((f = readFrame(in)) != null) {
            out.add(f);
        }
        return out;
    }

    /** Annex-B AU 中第一个 NAL 的 type（nal_unit_type），无起始码返回 -1。 */
    public static int firstNalType(byte[] annexB) {
        for (int i = 0; i + 3 < annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0) {
                if (annexB[i + 2] == 1) {
                    return annexB[i + 3] & 0x1F;
                }
                if (i + 4 < annexB.length && annexB[i + 2] == 0 && annexB[i + 3] == 1) {
                    return annexB[i + 4] & 0x1F;
                }
            }
        }
        return -1;
    }

    /** AU 内所有 NAL type 的位图（bit=1<<type），用于 SPS/PPS/IDR 门控。 */
    public static int nalTypeMask(byte[] annexB) {
        int mask = 0;
        for (int i = 0; i + 3 < annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0) {
                if (annexB[i + 2] == 1) {
                    mask |= 1 << (annexB[i + 3] & 0x1F);
                    i += 2;
                } else if (i + 4 < annexB.length && annexB[i + 2] == 0 && annexB[i + 3] == 1) {
                    mask |= 1 << (annexB[i + 4] & 0x1F);
                    i += 3;
                }
            }
        }
        return mask;
    }
}
