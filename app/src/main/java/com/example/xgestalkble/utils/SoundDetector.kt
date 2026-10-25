package com.example.xgestalkble.utils

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * The "environmental ears" for deaf users: continuously listens to the mic and
 * fires an event when a loud / sudden sound happens (doorbell, knock, horn,
 * alarm, someone calling out…) so the app can alert by vibration, screen flash
 * and flashlight strobe.
 *
 * This base implementation is fully offline and needs no model — it detects
 * sound *energy* (loudness) above a tunable [threshold] with a refractory gap so
 * one real-world sound = one alert. It cannot yet name the sound type.
 *
 * Upgrade path: drop a YAMNet TFLite model into assets and classify each
 * triggered window to report the actual category (doorbell vs horn vs baby cry).
 * The screen already shows a generic "Loud sound" label until then.
 */
class SoundDetector {

    companion object {
        private const val TAG = "SoundDetector"
        const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 100
        private const val REFRACTORY_MS = 1200 // one alert per real-world sound
    }

    @Volatile private var running = false
    private var worker: Thread? = null

    /**
     * RMS energy (0..1) a frame must reach to count as an "alert" sound. Lower =
     * more sensitive. Driven by the sensitivity slider on the screen.
     */
    @Volatile var threshold = 0.18f

    /** Fired (background thread) when a qualifying sound is detected; arg = peak level 0..1. */
    var onSound: ((Float) -> Unit)? = null
    /** Continuous smoothed level 0..1 for the live mic meter. */
    var onLevel: ((Float) -> Unit)? = null
    /** Fatal capture error (mic unavailable / permission). */
    var onError: ((String) -> Unit)? = null

    fun isRunning(): Boolean = running

    /** Start listening. RECORD_AUDIO must already be granted. */
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError?.invoke("Microphone is not available on this device.")
            return
        }
        val bufSize = maxOf(minBuf, SAMPLE_RATE)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: SecurityException) {
            onError?.invoke("Microphone permission is required for sound alerts.")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError?.invoke("Could not initialize the microphone.")
            return
        }

        running = true
        worker = thread(name = "sound-detector") { captureLoop(record) }
    }

    fun stop() {
        running = false
        worker?.let { runCatching { it.join(500) } }
        worker = null
    }

    private fun captureLoop(record: AudioRecord) {
        val frame = ShortArray(SAMPLE_RATE / 1000 * FRAME_MS)
        var smoothed = 0f
        var refractoryMs = 0

        try {
            record.startRecording()
        } catch (e: Exception) {
            onError?.invoke("Listening failed to start: ${e.message}")
            running = false
            runCatching { record.release() }
            return
        }

        while (running) {
            val n = record.read(frame, 0, frame.size)
            if (n <= 0) continue

            var sumSq = 0.0
            for (i in 0 until n) {
                val s = frame[i] / 32768f
                sumSq += (s * s).toDouble()
            }
            val rms = sqrt(sumSq / n).toFloat()

            // Smooth for a calm-looking VU meter.
            smoothed = smoothed * 0.6f + rms * 0.4f
            onLevel?.invoke(smoothed.coerceIn(0f, 1f))

            if (refractoryMs > 0) {
                refractoryMs -= FRAME_MS
            } else if (rms >= threshold) {
                refractoryMs = REFRACTORY_MS
                try {
                    onSound?.invoke(rms.coerceIn(0f, 1f))
                } catch (e: Exception) {
                    Log.e(TAG, "onSound handler failed: ${e.message}")
                }
            }
        }

        runCatching { record.stop() }
        runCatching { record.release() }
    }
}
