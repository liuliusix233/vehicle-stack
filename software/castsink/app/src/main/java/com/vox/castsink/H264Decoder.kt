package com.vox.castsink

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.ArrayDeque

/**
 * MediaCodec 异步模式 video/avc 解码，输出到 Surface。
 * 借鉴：NativeWfdSink 的解码器管理（configure→start→异步回调→异常重建）。
 * SPS/PPS/IDR：收齐 SPS+PPS 前不喂帧；新 SPS 到来（分辨率可能变化）重建解码器。
 * 坏帧处理：输入/输出异常 → release 并在新帧到达时重建；背压丢最旧帧。
 */
class H264Decoder(private val surface: Surface) {

    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME = "video/avc"
        private const val MAX_QUEUE = 4
    }

    private val lock = Any()
    private val pending = ArrayDeque<ByteArray>()
    private var codec: MediaCodec? = null
    private var sawSps = false
    private var sawPps = false
    @Volatile private var released = false

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            synchronized(lock) {
                if (released || codec !== c) return
                val frame = pending.poll()
                try {
                    if (frame == null) {
                        c.queueInputBuffer(index, 0, 0, timestampUs(), 0)
                    } else {
                        val buf = c.getInputBuffer(index)
                        if (buf == null) {
                            pending.addFirst(frame)
                            return
                        }
                        buf.position(0)
                        buf.put(frame)
                        c.queueInputBuffer(index, 0, frame.size, timestampUs(), 0)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "input error: ${e.message}")
                    rebuildLocked()
                }
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                c.releaseOutputBuffer(index, true)
            } catch (e: Exception) {
                Log.w(TAG, "output error: ${e.message}")
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "codec error: ${e.message}")
            synchronized(lock) {
                if (!released && codec === c) rebuildLocked()
            }
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "output format changed: $format")
        }
    }

    fun start() {
        synchronized(lock) {
            released = false
            if (codec == null) createCodec()
        }
    }

    /** 喂一帧 Annex-B 数据（含起始码）。SPS/PPS 未就绪时丢弃（坏帧策略）。 */
    fun feed(frame: ByteArray) {
        if (frame.isEmpty()) return
        synchronized(lock) {
            if (released) return
            val nal = scanNalTypes(frame)
            if (nal.sps) {
                if (sawSps && codec != null) {
                    // 新 SPS：分辨率可能变化，MediaCodec 无法热改 → 重建
                    rebuildLocked()
                }
                sawSps = true
            }
            if (nal.pps) sawPps = true
            if (!sawSps || !sawPps) return
            if (codec == null) createCodec()
            if (codec == null) return
            if (pending.size >= MAX_QUEUE) pending.poll()
            pending.add(frame)
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            pending.clear()
            destroyCodecLocked()
        }
    }

    private fun createCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MIME, 1280, 720)
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, 1920)
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, 1088)
            val c = MediaCodec.createDecoderByType(MIME)
            c.configure(format, surface, null, 0)
            c.setCallback(callback)
            c.start()
            codec = c
            Log.i(TAG, "decoder created (async)")
        } catch (e: Exception) {
            Log.e(TAG, "createCodec failed: ${e.message}")
            destroyCodecLocked()
        }
    }

    private fun rebuildLocked() {
        Log.i(TAG, "rebuilding decoder")
        destroyCodecLocked()
        sawSps = false
        sawPps = false
        pending.clear()
        createCodec()
    }

    private fun destroyCodecLocked() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    private fun timestampUs(): Long = System.nanoTime() / 1000L

    /** 扫描 Annex-B 起始码，识别 NAL 类型：7=SPS 8=PPS 5=IDR。 */
    private fun scanNalTypes(frame: ByteArray): NalTypes {
        var sps = false
        var pps = false
        var idr = false
        var i = 0
        while (i + 3 < frame.size) {
            if (frame[i] == 0.toByte() && frame[i + 1] == 0.toByte() && frame[i + 2] == 1.toByte()) {
                when (frame[i + 3].toInt() and 0x1F) {
                    7 -> sps = true
                    8 -> pps = true
                    5 -> idr = true
                }
                i += 4
            } else {
                i++
            }
        }
        return NalTypes(sps, pps, idr)
    }

    private class NalTypes(val sps: Boolean, val pps: Boolean, val idr: Boolean)
}
