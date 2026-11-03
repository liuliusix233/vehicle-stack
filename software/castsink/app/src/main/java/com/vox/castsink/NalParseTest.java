package com.vox.castsink;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 桌面 JVM 可跑的 NAL 解析验证（build 后用 java -cp build/classes:android.jar 执行）。
 * 覆盖：长度前缀拆帧 / readFrame / firstNalType / nalTypeMask。
 */
public final class NalParseTest {

    private static int checks = 0;
    private static int failed = 0;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static byte[] framed(byte[] payload) {
        byte[] out = new byte[4 + payload.length];
        int len = payload.length;
        out[0] = (byte) (len >>> 24);
        out[1] = (byte) (len >>> 16);
        out[2] = (byte) (len >>> 8);
        out[3] = (byte) len;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static void main(String[] args) throws Exception {
        byte[] sps = new byte[]{0, 0, 0, 1, 0x67, 1, 2, 3};          // 4B start code + SPS
        byte[] pps = new byte[]{0, 0, 0, 1, 0x68, 4, 5};             // 4B start code + PPS
        byte[] idr = new byte[]{0, 0, 1, 0x65, 6, 7, 8};             // 3B start code + IDR

        // 1. splitLengthPrefixed
        byte[] buf = concat(framed(sps), framed(pps));
        List<byte[]> frames = H264TcpReceiver.splitLengthPrefixed(buf);
        check(frames.size() == 2, "splitLengthPrefixed count==2");
        check(Arrays.equals(frames.get(0), sps), "split frame0 content");
        check(Arrays.equals(frames.get(1), pps), "split frame1 content");

        // 2. readFrame 直读 + EOF
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(buf));
        check(Arrays.equals(H264TcpReceiver.readFrame(in), sps), "readFrame 0");
        check(Arrays.equals(H264TcpReceiver.readFrame(in), pps), "readFrame 1");
        check(H264TcpReceiver.readFrame(in) == null, "readFrame EOF -> null");

        // 3. firstNalType（3B/4B 起始码、无起始码）
        check(H264TcpReceiver.firstNalType(sps) == 7, "firstNalType SPS==7");
        check(H264TcpReceiver.firstNalType(pps) == 8, "firstNalType PPS==8");
        check(H264TcpReceiver.firstNalType(idr) == 5, "firstNalType IDR==5");
        check(H264TcpReceiver.firstNalType(new byte[]{1, 2, 3, 4}) == -1, "firstNalType no-startcode==-1");

        // 4. nalTypeMask（多 NAL AU）
        byte[] bundle = concat(framed(sps), framed(concat(pps, idr)));
        List<byte[]> bframes = H264TcpReceiver.splitLengthPrefixed(bundle);
        int mask = H264TcpReceiver.nalTypeMask(bframes.get(1));
        check((mask & (1 << 8)) != 0 && (mask & (1 << 5)) != 0, "nalTypeMask PPS+IDR");
        int maskAll = H264TcpReceiver.nalTypeMask(concat(sps, concat(pps, idr)));
        check((maskAll & (1 << 7)) != 0 && (maskAll & (1 << 8)) != 0 && (maskAll & (1 << 5)) != 0,
                "nalTypeMask SPS+PPS+IDR");

        // 5. 坏长度前缀应抛 IOException
        boolean threw = false;
        try {
            H264TcpReceiver.splitLengthPrefixed(new byte[]{0, 0, 1, 2, 3});
        } catch (java.io.IOException e) {
            threw = true;
        }
        check(threw, "bad length prefix -> IOException");

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
