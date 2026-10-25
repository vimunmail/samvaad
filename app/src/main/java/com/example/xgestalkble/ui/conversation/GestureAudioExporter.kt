package com.example.xgestalkble.ui.conversation

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.FileProvider
import com.example.xgestalkble.utils.LanguageConfig
import com.example.xgestalkble.utils.TtsVoiceGender
import com.example.xgestalkble.utils.TtsVoiceTone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Renders a gesture's spoken phrase to a shareable audio file using the system
 * TTS voice: synthesize → WAV → AAC/M4A (Android ships no MP3 *encoder*, and
 * .m4a plays and shares everywhere — WhatsApp, Gmail, Drive — like a voice note).
 *
 * Uses its OWN TextToSpeech engine so exporting never interrupts the live chat
 * voice. Files are cached under `files/gesture_audio/` keyed by phrase+language,
 * so each gesture is only synthesized once.
 */
object GestureAudioExporter {
    private const val TAG = "GestureAudioExporter"

    private var tts: TextToSpeech? = null
    private var initDeferred: CompletableDeferred<Boolean>? = null

    /** Initialize the dedicated export engine (idempotent). True when usable. */
    suspend fun ensureReady(context: Context): Boolean {
        initDeferred?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        initDeferred = deferred
        tts = TextToSpeech(context.applicationContext) { status ->
            deferred.complete(status == TextToSpeech.SUCCESS)
        }
        return deferred.await()
    }

    fun audioDir(context: Context): File =

        File(context.filesDir, "gesture_audio").apply {
            mkdirs()
            // Protect existing clips too on older Android versions with shared storage access.
            runCatching {
                val legacy = File(context.getExternalFilesDir(null), "gesture_audio")
                legacy.listFiles { file -> file.isFile && file.extension in listOf("m4a", "wav") }?.forEach { file ->
                    val target = File(this, file.name)
                    if (!target.exists()) file.copyTo(target)
                    if (target.length() == file.length()) file.delete()
                }
                legacy.delete()
            }
        }

    /** Stable per-phrase file so repeated opens reuse the already-made clip.
     *  The voice style is part of the key, so changing gender/tone regenerates
     *  the clip in the new voice instead of replaying the cached old one. */
    fun fileFor(
        context: Context,
        spoken: String,
        locale: Locale,
        gender: TtsVoiceGender = LanguageConfig.voiceGender,
        tone: TtsVoiceTone = LanguageConfig.voiceTone
    ): File {
        val slug = spoken.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\u0080-\\uFFFF]+"), "_")
            .trim('_').take(40).ifEmpty { "gesture" }
        val style = "${gender.name}_${tone.name}"
            .lowercase(Locale.US)
        return File(audioDir(context), "${slug}_${locale.language}_$style.m4a")
    }

    /**
     * Synthesize [spoken] in [locale] to a cached .m4a. Returns the file, or null
     * when the voice is missing or synthesis/encoding fails.
     */
    suspend fun synthesize(
        context: Context,
        spoken: String,
        locale: Locale,
        gender: TtsVoiceGender = LanguageConfig.voiceGender,
        tone: TtsVoiceTone = LanguageConfig.voiceTone
    ): File? {
        val out = fileFor(context, spoken, locale, gender, tone)
        if (out.exists() && out.length() > 0) return out
        if (!ensureReady(context)) return null

        val wav = File(audioDir(context), "${out.nameWithoutExtension}.wav")
        return withContext(Dispatchers.IO) {
            try {
                if (!synthesizeWav(spoken, locale, wav, gender, tone)) return@withContext null
                wavToM4a(wav, out)
                out.takeIf { it.exists() && it.length() > 0 }
            } catch (e: Exception) {
                Log.e(TAG, "Audio export failed (${e.javaClass.simpleName})")
                out.delete()
                null
            } finally {
                wav.delete()
            }
        }
    }

    private suspend fun synthesizeWav(
        spoken: String,
        locale: Locale,
        wav: File,
        gender: TtsVoiceGender = LanguageConfig.voiceGender,
        tone: TtsVoiceTone = LanguageConfig.voiceTone
    ): Boolean {
        val engine = tts ?: return false
        val result = engine.setLanguage(locale)
        val fellBack = result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED
        if (fellBack) {
            Log.w(TAG, "Requested language voice data is unavailable; audio export skipped.")
            return false
        }
        
        // Temporarily style external engine with requested gender and tone
        val oldGender = com.example.xgestalkble.utils.TextToSpeechHelper.voiceGender
        val oldTone = com.example.xgestalkble.utils.TextToSpeechHelper.voiceTone
        com.example.xgestalkble.utils.TextToSpeechHelper.setVoiceStyle(gender, tone)
        com.example.xgestalkble.utils.TextToSpeechHelper
            .styleExternalEngine(engine, locale)
        com.example.xgestalkble.utils.TextToSpeechHelper.setVoiceStyle(oldGender, oldTone)

        return suspendCancellableCoroutine { cont ->
            val id = "audio_export_${System.nanoTime()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(true)
                }
                @Deprecated("deprecated in API level 21")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(false)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) cont.resume(false)
                }
            })
            val queued = engine.synthesizeToFile(
                com.example.xgestalkble.utils.TextToSpeechHelper.toSpeakable(spoken.trim()),
                Bundle(), wav, id
            )
            if (queued != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        }
    }

    // ── Sharing ──

    fun share(context: Context, file: File, label: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            putExtra(Intent.EXTRA_SUBJECT, "Gesture audio: $label")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchChooser(context, intent, "Share gesture audio")
    }

    fun shareAll(context: Context, files: List<File>, title: String) {
        if (files.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM, ArrayList(files.map { uriFor(context, it) })
            )
            putExtra(Intent.EXTRA_SUBJECT, "Gesture audio: $title")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchChooser(context, intent, "Share all gesture audio")
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun launchChooser(context: Context, intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    // ── WAV → M4A (AAC-LC via MediaCodec + MediaMuxer) ──

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun wavToM4a(wav: File, out: File) {
        // TTS clips are a few seconds, so reading whole files is fine.
        val bytes = wav.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "Not a WAV file" }

        var sampleRate = 22050
        var channels = 1
        var dataOffset = -1
        var dataSize = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            when (id) {
                "fmt " -> {
                    channels = le16(bytes, pos + 10)
                    sampleRate = le32(bytes, pos + 12)
                }
                "data" -> {
                    dataOffset = pos + 8
                    dataSize = size
                }
            }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset >= 0) { "WAV has no data chunk" }
        dataSize = minOf(dataSize, bytes.size - dataOffset)
        require(dataSize > 0) { "WAV data chunk is empty" }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            var track = -1
            var inPos = dataOffset
            val inEnd = dataOffset + dataSize
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val bytesPerSec = sampleRate.toLong() * channels * 2L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val chunk = minOf(buf.capacity(), inEnd - inPos)
                        val ptsUs = (inPos - dataOffset) * 1_000_000L / bytesPerSec
                        if (chunk <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.clear()
                            buf.put(bytes, inPos, chunk)
                            codec.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0)
                            inPos += chunk
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            muxer.writeSampleData(track, codec.getOutputBuffer(outIdx)!!, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
