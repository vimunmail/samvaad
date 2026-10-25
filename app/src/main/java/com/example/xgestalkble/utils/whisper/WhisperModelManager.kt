package com.example.xgestalkble.utils.whisper

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the offline Whisper model files so the app is self-contained: the
 * model is downloaded ONCE (the only time the internet is used for speech) into
 * the app's private storage, then every transcription afterwards is fully
 * offline. This is what makes the speech-to-text "standalone".
 *
 * Files live in `filesDir/whisper/`:
 *   - the Whisper model    (TFLite, base multilingual ≈ 142 MB)
 *   - the filters + vocab  (mel filterbank + token table the engine needs)
 *
 * URLs are overridable so you can host the assets wherever you like (or point at
 * a bundled copy). [ensureModel] reports progress and is safe to cancel/retry —
 * it downloads to a `.part` file and only swaps it in on a complete, correctly
 * sized download, so a dropped connection never leaves a corrupt model.
 */
class WhisperModelManager(private val context: Context) {

    companion object {
        private const val TAG = "WhisperModelManager"

        // Base multilingual Whisper (English + Malayalam). Override if you self-host.
        const val MODEL_FILE = "whisper-base.tflite"
        const val VOCAB_FILE = "filters_vocab_multilingual.bin"

        // Expected sizes guard against truncated/HTML-error downloads (0 = skip check).
        private const val MODEL_MIN_BYTES = 70_000_000L   // base on HF is ~78.7 MB
        private const val VOCAB_MIN_BYTES = 500_000L      // vocab on HF is ~572 KB
    }

    /** Default download sources — replace with your own mirror if preferred. */
    var modelUrl =
        "https://huggingface.co/cik009/whisper/resolve/main/whisper-base.tflite"
    var vocabUrl =
        "https://huggingface.co/cik009/whisper/resolve/main/filters_vocab_multilingual.bin"

    private val dir: File get() = File(context.filesDir, "whisper").apply { mkdirs() }

    val modelFile: File get() = File(dir, MODEL_FILE)
    val vocabFile: File get() = File(dir, VOCAB_FILE)

    /** True once both files are present and big enough to be real. */
    fun isReady(): Boolean =
        modelFile.exists() && modelFile.length() >= MODEL_MIN_BYTES &&
            vocabFile.exists() && vocabFile.length() >= VOCAB_MIN_BYTES

    /**
     * Ensure both files are present, downloading whatever is missing.
     *
     * @param onProgress fraction in [0,1] across the whole job, plus a human label.
     * @return true if everything is ready afterwards.
     *
     * Call this OFF the main thread (it does blocking IO).
     */
    fun ensureModel(onProgress: (fraction: Float, label: String) -> Unit): Boolean {
        if (isReady()) { onProgress(1f, "Ready"); return true }
        return try {
            // Two files, weighted by size for a smooth overall bar.
            if (!fileOk(vocabFile, VOCAB_MIN_BYTES)) {
                download(vocabUrl, vocabFile, VOCAB_MIN_BYTES) { f ->
                    onProgress(f * 0.05f, "Downloading language data…")
                }
            }
            if (!fileOk(modelFile, MODEL_MIN_BYTES)) {
                download(modelUrl, modelFile, MODEL_MIN_BYTES) { f ->
                    onProgress(0.05f + f * 0.95f, "Downloading speech model…")
                }
            }
            val ok = isReady()
            onProgress(if (ok) 1f else 0f, if (ok) "Ready" else "Download incomplete")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${e.message}")
            onProgress(0f, "Download failed: ${e.message}")
            false
        }
    }

    /** Delete the downloaded model (e.g. to re-download or free space). */
    fun clear() {
        runCatching { modelFile.delete() }
        runCatching { vocabFile.delete() }
    }

    private fun fileOk(f: File, minBytes: Long): Boolean =
        f.exists() && f.length() >= minBytes

    private fun download(
        urlStr: String,
        dest: File,
        minBytes: Long,
        onProgress: (Float) -> Unit
    ) {
        val part = File(dest.parentFile, dest.name + ".part")
        runCatching { part.delete() }

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} for $urlStr")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        output.write(buf, 0, read)
                        done += read
                        if (total != null) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }

        if (part.length() < minBytes) {
            runCatching { part.delete() }
            throw IllegalStateException("Downloaded file too small (${part.length()} bytes) — check the URL/connection.")
        }
        if (dest.exists()) runCatching { dest.delete() }
        if (!part.renameTo(dest)) {
            throw IllegalStateException("Could not finalize ${dest.name}.")
        }
    }
}
