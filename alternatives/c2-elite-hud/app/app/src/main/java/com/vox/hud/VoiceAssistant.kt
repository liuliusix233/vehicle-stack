package com.vox.hud

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Voice Assistant — continuous microphone capture + HTTP POST to voice server.
 *
 * Architecture:
 *  - Background thread records 2-second PCM chunks (16kHz, 16-bit, mono)
 *  - Each chunk is POSTed to Pi proxy voice endpoint
 *  - Server returns JSON with state (idle/waking/listening/processing/speaking)
 *  - State is broadcast to dashboard via VoiceStateCallback
 *
 * Thread-safety: all state reads/writes on the capture thread.
 */
class VoiceAssistant(
    private val serverUrl: String,
    private val stateCallback: VoiceStateCallback
) {

    companion object {
        private const val TAG = "VoiceAsst"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_DURATION_MS = 2000  // 2 seconds per chunk
        private const val CHUNK_SIZE = SAMPLE_RATE * 2 * CHUNK_DURATION_MS / 1000  // 64000 bytes
        private const val POLL_INTERVAL_MS = 500     // check for state changes during playback
        private const val TTS_DIR = "/data/data/com.vox.hud/files/tts/"
        
        // State constants (must match server)
        const val STATE_IDLE = "idle"
        const val STATE_WAKING = "waking"
        const val STATE_LISTENING = "listening"
        const val STATE_PROCESSING = "processing"
        const val STATE_SPEAKING = "speaking"
    }

    fun interface VoiceStateCallback {
        fun onStateChanged(state: String, text: String, ttsUrl: String?)
    }

    private var isRunning = false
    private var captureThread: Thread? = null
    private var currentState = STATE_IDLE
    private var ttsUrlPending: String? = null
    private var ttsText: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Starting voice assistant (server: $serverUrl)")

        // Ensure TTS directory exists
        try {
            val dir = java.io.File(TTS_DIR)
            dir.mkdirs()
        } catch (_: Exception) {}

        captureThread = object : Thread("VoiceCapture") {
            override fun run() {
                Log.d(TAG, "Capture thread started")
                var recorder: AudioRecord? = null
                val buffer = ByteArray(CHUNK_SIZE)

                try {
                    val minBufSize = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBufSize <= 0) {
                        Log.e(TAG, "Invalid buffer size: $minBufSize")
                        return
                    }

                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufSize.coerceAtLeast(CHUNK_SIZE * 2)  // 2 chunks buffer
                    )

                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord init failed")
                        return
                    }

                    recorder.startRecording()
                    Log.d(TAG, "Recording started (bufferSize=${buffer.size})")

                    while (isRunning) {
                        val bytesRead = recorder.read(buffer, 0, buffer.size)
                        if (bytesRead <= 0) continue

                        // Send audio chunk to server
                        val response = postAudioChunk(buffer, bytesRead)
                        if (response != null) {
                            val newState = response["state"] ?: STATE_IDLE
                            val text = response["text"] ?: ""
                            val ttsFile = response["tts_url"]
                            val ttsTextFromServer = response["tts_text"] ?: ""

                            if (newState != currentState) {
                                currentState = newState
                                if (newState == STATE_SPEAKING) {
                                    ttsUrlPending = ttsFile
                                    ttsText = ttsTextFromServer
                                }
                                Log.d(TAG, "State change: $newState text='$text'")
                                
                                // Construct full TTS URL from server base URL
                                val fullTtsUrl = if (ttsFile != null && !ttsFile.startsWith("http")) {
                                    "${serverUrl.trimEnd('/')}$ttsFile"
                                } else {
                                    ttsFile
                                }
                                
                                // Notify callback on main thread (HudService handles download+play)
                                val s = newState
                                val t = text
                                val url = if (newState == STATE_SPEAKING) fullTtsUrl else null
                                mainHandler.post { stateCallback.onStateChanged(s, t, url) }
                            }
                        }

                        // Slight delay to prevent tight loop if server responds quickly
                        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    }

                    recorder.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Capture error: ${e.message}")
                } finally {
                    try { recorder?.release() } catch (_: Exception) {}
                    currentState = STATE_IDLE
                    mainHandler.post { stateCallback.onStateChanged(STATE_IDLE, "", null) }
                    Log.d(TAG, "Capture thread ended")
                }
            }
        }.apply { isDaemon = true }
        captureThread?.start()
    }

    fun stop() {
        isRunning = false
        captureThread?.interrupt()
        try { captureThread?.join(2000) } catch (_: Exception) {}
        captureThread = null
        Log.d(TAG, "Voice assistant stopped")
    }

    fun getCurrentState(): String = currentState

    /** POST audio chunk to server and parse JSON response. */
    private fun postAudioChunk(pcmData: ByteArray, validBytes: Int): Map<String, String>? {
        return try {
            val url = URL("$serverUrl/audio")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            
            // Send only valid bytes
            conn.outputStream.use { out ->
                out.write(pcmData, 0, validBytes)
                out.flush()
            }
            
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().readText()
                parseSimpleJson(jsonStr)
            } else {
                Log.w(TAG, "Server returned $responseCode")
                null
            }
        } catch (e: Exception) {
            // Don't log every error — just return null (network issues are expected)
            if (currentState != STATE_IDLE) {
                Log.w(TAG, "POST error: ${e.message}")
            }
            null
        }
    }

    /** Minimal JSON parser (no external deps). Returns first-level string values. */
    private fun parseSimpleJson(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return result
        
        val inner = trimmed.substring(1, trimmed.length - 1)
        var i = 0
        while (i < inner.length) {
            // Skip whitespace
            while (i < inner.length && inner[i] <= ' ') i++
            if (i >= inner.length) break
            
            // Parse key
            if (inner[i] != '"') break
            val keyStart = i + 1
            val keyEnd = inner.indexOf('"', keyStart)
            if (keyEnd < 0) break
            val key = inner.substring(keyStart, keyEnd)
            i = keyEnd + 1
            
            // Skip colon
            while (i < inner.length && inner[i] != ':') i++
            i++
            while (i < inner.length && inner[i] <= ' ') i++
            
            // Parse value
            if (i >= inner.length) break
            if (inner[i] == '"') {
                // String value
                val valStart = i + 1
                val valEnd = inner.indexOf('"', valStart)
                if (valEnd < 0) {
                    result[key] = inner.substring(valStart)
                    break
                }
                result[key] = inner.substring(valStart, valEnd)
                i = valEnd + 1
            } else {
                // Number or boolean
                val valEnd = inner.indexOf(',', i).let { if (it < 0) inner.length else it }
                result[key] = inner.substring(i, valEnd).trim()
                i = valEnd
            }
            
            // Skip comma
            while (i < inner.length && (inner[i] == ',' || inner[i] <= ' ')) i++
        }
        
        return result
    }
}
