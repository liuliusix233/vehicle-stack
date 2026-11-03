package com.vox.castsink;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

/**
 * MediaCodec 异步模式 H.264 解码器，输出到 Surface。
 * 对标凯迪拉克 SharedVideoSession（MediaCodec video/avc @60fps）：
 * - SPS/PPS 门控：收齐 SPS+PPS 且等到 IDR 才开始喂帧（此前帧计为 dropped）
 * - surface 热切换：setOutputSurface ≈ staged retarget 三段式的 M1 简化
 *   （凯迪是 attachStagedPrimaryOutput→canCommit→commit/rollback）
 */
public final class H264Decoder {

    private static final String TAG = "H264Decoder";
    private static final String MIME = "video/avc";
    private static final int DEFAULT_W = 1280;
    private static final int DEFAULT_H = 720;

    static final int NAL_SPS = 7;
    static final int NAL_PPS = 8;
    static final int NAL_IDR = 5;

    private final Object lock = new Object();
    private final Deque<byte[]> inputQueue = new ArrayDeque<>();
    private final ByteArrayOutputStream pendingConfig = new ByteArrayOutputStream();

    private MediaCodec codec;
    private Surface surface;
    private boolean configured;
    private boolean streamReady;
    private boolean sawSps;
    private boolean sawPps;
    private long queued, rendered, dropped;
    private long lastPtsUs;

    private final MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override
        public void onQueueInputBuffer(MediaCodec c, int index) {
            byte[] data;
            synchronized (lock) {
                data = inputQueue.poll();
                if (data == null) return; // codec 主动要帧但无数据：不消费，等 queue() 再投
            }
            try {
                ByteBuffer ib = c.getInputBuffer(index);
                if (ib != null) {
                    ib.position(0);
                    ib.put(data);
                    c.queueInputBuffer(index, 0, data.length, ptsFor(data.length), 0);
                    synchronized (lock) { queued++; }
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "queueInputBuffer failed", e);
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec c, int index, MediaCodec.BufferInfo info) {
            try {
                c.releaseOutputBuffer(index, surface != null);
            } catch (IllegalStateException e) {
                Log.w(TAG, "releaseOutputBuffer failed", e);
            }
            synchronized (lock) { rendered++; }
        }

        @Override
        public void onError(MediaCodec c, MediaCodec.CodecException e) {
            Log.e(TAG, "codec error", e);
        }

        @Override
        public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {
            Log.i(TAG, "output format: " + f);
        }
    };

    /**
     * 绑定输出 Surface。已配置时走 setOutputSurface（surface 热切换），
     * 否则创建并 configure/start 解码器。
     */
    public void attach(Surface s) throws java.io.IOException {
        synchronized (lock) {
            if (configured && codec != null) {
                try {
                    codec.setOutputSurface(s); // staged retarget 简化：attach 新 surface 即 commit
                    surface = s;
                    Log.i(TAG, "staged retarget: output surface swapped");
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "setOutputSurface failed", e);
                }
                return;
            }
            codec = MediaCodec.createDecoderByType(MIME);
            MediaFormat f = MediaFormat.createVideoFormat(MIME, DEFAULT_W, DEFAULT_H);
            codec.configure(f, s, null, 0);
            codec.setCallback(callback, null);
            codec.start();
            configured = true;
            surface = s;
            Log.i(TAG, "decoder configured " + DEFAULT_W + "x" + DEFAULT_H + " -> surface");
        }
    }

    /** 投一帧 Annex-B AccessUnit；含 SPS/PPS/IDR 门控与关键帧等待。 */
    public void queue(byte[] au) {
        if (au == null || au.length == 0) return;
        synchronized (lock) {
            if (codec == null) return;
            int mask = H264TcpReceiver.nalTypeMask(au);
            boolean hasSps = (mask & (1 << NAL_SPS)) != 0;
            boolean hasPps = (mask & (1 << NAL_PPS)) != 0;
            boolean hasIdr = (mask & (1 << NAL_IDR)) != 0;

            byte[] toFeed;
            if (!streamReady) {
                if (hasSps) sawSps = true;
                if (hasPps) sawPps = true;
                if (!hasIdr || !(sawSps && sawPps)) {
                    // 参数集先缓存；非 IDR 帧在等到关键帧前一律丢弃
                    try { pendingConfig.write(au, 0, au.length); } catch (java.io.IOException ignored) { }
                    if (!hasSps && !hasPps) dropped++;
                    return;
                }
                byte[] cfg = pendingConfig.toByteArray();
                byte[] merged = new byte[cfg.length + au.length];
                System.arraycopy(cfg, 0, merged, 0, cfg.length);
                System.arraycopy(au, 0, merged, cfg.length, au.length);
                toFeed = merged;
                streamReady = true;
                Log.i(TAG, "stream ready at first IDR (config " + cfg.length + "B + au " + au.length + "B)");
            } else {
                toFeed = au;
            }
            inputQueue.add(toFeed);
        }
    }

    /** 简单 pts：单调递增，33.3ms 步进与墙钟取大者。 */
    private long ptsFor(int size) {
        long nowUs = System.nanoTime() / 1000;
        lastPtsUs = Math.max(lastPtsUs + 33333, nowUs);
        return lastPtsUs;
    }

    public void flush() {
        synchronized (lock) {
            if (codec == null) return;
            try { codec.flush(); } catch (IllegalStateException ignored) { }
            inputQueue.clear();
            pendingConfig.reset();
            streamReady = false;
            sawSps = false;
            sawPps = false;
        }
    }

    public void release() {
        synchronized (lock) {
            if (codec == null) return;
            try { codec.stop(); } catch (IllegalStateException ignored) { }
            try { codec.release(); } catch (IllegalStateException ignored) { }
            codec = null;
            configured = false;
            streamReady = false;
            sawSps = false;
            sawPps = false;
            pendingConfig.reset();
            inputQueue.clear();
        }
    }

    public long queuedFrames()  { synchronized (lock) { return queued; } }
    public long renderedFrames() { synchronized (lock) { return rendered; } }
    public long droppedFrames()  { synchronized (lock) { return dropped; } }
}
