package com.example.xgestalkble.utils.whisper

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt
// NOTE: Whisper offline engine (Stage 2) is not wired yet; the chat speech path
// uses SpeechToTextHelper. This recorder is kept for the offline engine.

/**
 * Captures microphone audio in the exact format Whisper expects: 16 kHz, mono,
 * normalized float samples in [-1, 1]. Used by [WhisperSpeechRecognizer] for the
 * fully-offline speech-to-text path.
 *
 * It runs a simple energy-based endpointer (voice-activity detection): it waits
 * for speech to start, then collects samples until it hears a short trailing
 * silence, and hands that utterance back via [onUtterance]. Then it keeps
 * listening for the next phrase — so the hearing person can talk continuously
 * without re-tapping the mic, mirroring the old [SpeechToTextHelper] behavior.
 *
 * Whisper takes up to 30 s of audio per inference; longer phrases are cut at
 * [MAX_UTTERANCE_SEC] so a single forward pass stays bounded.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16_000              // Whisper's required input rate
        private const val MAX_UTTERANCE_SEC = 28    // keep under Whisper's 30 s window
        // MAX sensitivity: threshold low enough to catch soft / distant speech.
        private const val SILENCE_RMS = 0.004f      // below this ⇒ "silence"
        private const val SILENCE_HANG_MS = 900     // trailing silence that ends a phrase
        private const val MIN_SPEECH_MS = 200       // ignore blips shorter than this
        private const val PRE_ROLL_FRAMES = 3       // ~300 ms kept before speech onset
    }

    @Volatile private var running = false
    private var worker: Thread? = null

    /** Called on a background thread with one finished utterance (16 kHz float). */
    var onUtterance: ((FloatArray) -> Unit)? = null
    /** Speech detected / ended — drive a "listening…" indicator. */
    var onSpeechActive: ((Boolean) -> Unit)? = null
    /** Fatal capture error (e.g. mic unavailable / permission). */
    var onError: ((String) -> Unit)? = null

    fun isRunning(): Boolean = running

    /**
     * Start continuous capture. Requires RECORD_AUDIO to already be granted.
     * Safe to call once; call [stop] before starting again.
     */
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError?.invoke("Microphone is not available for recording.")
            return
        }
        val bufSize = maxOf(minBuf, SAMPLE_RATE) // ≥ ~1 s of headroom

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: SecurityException) {
            onError?.invoke("Microphone permission is required for voice-to-text.")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError?.invoke("Could not initialize the microphone.")
            return
        }

        running = true
        worker = thread(name = "whisper-audio") {
            captureLoop(record)
        }
    }

    fun stop() {
        running = false
        worker?.let { runCatching { it.join(500) } }
        worker = null
        onSpeechActive?.invoke(false)
    }

    private fun captureLoop(record: AudioRecord) {
        val frame = ShortArray(SAMPLE_RATE / 10) // 100 ms frames
        val utterance = ArrayList<Float>(SAMPLE_RATE * 4)
        // Ring buffer of the frames just before speech onset — prepended to the
        // utterance so a soft first syllable isn't clipped by the VAD.
        val preRoll = ArrayDeque<FloatArray>()
        var speaking = false
        var silenceMs = 0
        var speechMs = 0

        // Boost quiet mics in hardware where supported.
        val agc = runCatching {
            if (AutomaticGainControl.isAvailable())
                AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true }
            else null
        }.getOrNull()
        val ns = runCatching {
            if (NoiseSuppressor.isAvailable())
                NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            else null
        }.getOrNull()

        try {
            record.startRecording()
        } catch (e: Exception) {
            onError?.invoke("Recording failed to start: ${e.message}")
            running = false
            runCatching { agc?.release() }
            runCatching { ns?.release() }
            runCatching { record.release() }
            return
        }

        while (running) {
            val n = record.read(frame, 0, frame.size)
            if (n <= 0) continue

            // RMS energy of this frame (normalized to [-1,1]).
            var sumSq = 0.0
            for (i in 0 until n) {
                val s = frame[i] / 32768f
                sumSq += (s * s).toDouble()
            }
            val rms = sqrt(sumSq / n).toFloat()
            val frameMs = n * 1000 / SAMPLE_RATE
            val voiced = rms >= SILENCE_RMS

            if (voiced) {
                if (!speaking) {
                    speaking = true
                    onSpeechActive?.invoke(true)
                    // Prepend the pre-roll so the quiet start of the phrase is kept.
                    for (chunk in preRoll) for (s in chunk) utterance.add(s)
                    preRoll.clear()
                }
                silenceMs = 0
                speechMs += frameMs
                appendFrame(frame, n, utterance)
            } else if (!speaking) {
                // Idle: remember the last ~300 ms so a soft onset isn't clipped.
                val copy = FloatArray(n)
                for (i in 0 until n) copy[i] = frame[i] / 32768f
                preRoll.addLast(copy)
                while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            } else {
                silenceMs += frameMs
                appendFrame(frame, n, utterance) // keep a little trailing audio
                if (silenceMs >= SILENCE_HANG_MS) {
                    if (speechMs >= MIN_SPEECH_MS && utterance.isNotEmpty()) {
                        emit(utterance)
                    }
                    utterance.clear()
                    speaking = false
                    silenceMs = 0
                    speechMs = 0
                    onSpeechActive?.invoke(false)
                }
            }

            // Cap a very long phrase so one Whisper pass stays bounded.
            if (utterance.size >= SAMPLE_RATE * MAX_UTTERANCE_SEC) {
                emit(utterance)
                utterance.clear()
                speaking = false
                silenceMs = 0
                speechMs = 0
                onSpeechActive?.invoke(false)
            }
        }

        runCatching { record.stop() }
        runCatching { agc?.release() }
        runCatching { ns?.release() }
        runCatching { record.release() }
    }

    private fun appendFrame(frame: ShortArray, n: Int, out: ArrayList<Float>) {
        for (i in 0 until n) out.add(frame[i] / 32768f)
    }

    private fun emit(samples: ArrayList<Float>) {
        val arr = FloatArray(samples.size)
        var peak = 0f
        for (i in samples.indices) {
            arr[i] = samples[i]
            val a = abs(arr[i])
            if (a > peak) peak = a
        }
        // Software gain: normalize quiet/distant speech up so Whisper hears it.
        if (peak > 1e-4f && peak < 0.95f) {
            val gain = 0.95f / peak
            for (i in arr.indices) arr[i] *= gain
        }
        try {
            onUtterance?.invoke(arr)
        } catch (e: Exception) {
            Log.e(TAG, "onUtterance handler failed: ${e.message}")
        }
    }
}
