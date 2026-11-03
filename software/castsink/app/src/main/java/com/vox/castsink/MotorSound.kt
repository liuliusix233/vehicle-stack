package com.vox.castsink

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * v84 "电动涡轮"音色（参考 BMW Hans Zimmer / Mercedes Silver Waves 的思路）：
 *  - 低频主体 70→160Hz 纯正弦：给"体感"，像大排量电机的底噪
 *  - 第二声部 2.5× 基频 + 0.3Hz 慢颤音：产生转动时的游移（不是机械谐波）
 *  - 第三声部 5× 极轻(6%)：高频"电流感"，只在功率上来时出现
 *  - 一层低通噪声"空气声"，随功率缓慢增加
 * 没有任何 2/3 次谐波、没有硬噪声 —— 这两样才会听起来像发动机。
 */
object MotorSound {
    private const val TAG = "MotorSound"
    private const val SR = 44100
    private const val FRAME = 512

    @Volatile private var running = false
    @Volatile var gain = 0.32f
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    private var p1 = 0.0
    private var p2 = 0.0
    private var p3 = 0.0
    private var amp = 0.0
    private var f = 70.0
    private var pwFil = 0.0
    private var lfo = 0.0
    private var noiseLp = 0.0
    private var rnd = 987654321

    val isOn: Boolean get() = running

    fun toggle(ctx: Context): Boolean {
        if (running) { stop(); return false }
        start(ctx); return true
    }

    fun start(ctx: Context) {
        if (running) return
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(FRAME * 2 * 6)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = at
        running = true
        at.play()
        thread = Thread {
            val buf = ShortArray(FRAME)
            while (running) {
                val p = VehicleState.hybPowerW
                val raw = if (p < -9000f) 0.0 else abs(p).toDouble()
                pwFil += (raw - pwFil) * 0.0045          // tau ≈ 3s
                val n = min(1.0, pwFil / 4000.0)
                val tf = 70.0 + 90.0 * Math.pow(n, 0.9)
                val targetAmp = gain * (0.12 + 0.30 * Math.pow(n, 0.6))
                f += (tf - f) * 0.02
                amp += (targetAmp - amp) * 0.015
                val w1 = 2.0 * Math.PI * f / SR
                val w2 = 2.0 * Math.PI * f * 2.5 / SR
                val w3 = 2.0 * Math.PI * f * 5.0 / SR
                val lfoStep = 2.0 * Math.PI * 0.3 / SR
                val vibAmp = 0.004 * n
                for (i in 0 until FRAME) {
                    lfo += lfoStep
                    if (lfo > 6.283185307) lfo -= 6.283185307
                    val vib = 1.0 + vibAmp * sin(lfo)
                    p1 += w1 * vib; p2 += w2; p3 += w3
                    if (p1 > 6.283185307) p1 -= 6.283185307
                    if (p2 > 6.283185307) p2 -= 6.283185307
                    if (p3 > 6.283185307) p3 -= 6.283185307
                    rnd = rnd * 1103515245 + 12345
                    val white = ((rnd shr 16) and 0x7fff) / 16384.0 - 1.0
                    noiseLp += (white - noiseLp) * 0.02     // 低通，做成"空气声"
                    val s = sin(p1) + 0.30 * sin(p2) + 0.06 * sin(p3) + 0.05 * n * noiseLp
                    buf[i] = ((amp * s / 1.41).coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
                }
                try { track?.write(buf, 0, FRAME) } catch (_: Exception) { }
            }
        }.apply { isDaemon = true }
        thread?.start()
        Log.i(TAG, "motor sound started (v84 turbine layers)")
    }

    fun stop() {
        running = false
        try { thread?.join(300) } catch (_: Exception) { }
        thread = null
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        amp = 0.0
        Log.i(TAG, "motor sound stopped")
    }
}
