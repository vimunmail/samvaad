package com.example.xgestalkble.utils

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * User-selectable gender for the spoken (sign → audio) voice. When the device's
 * TTS engine has a voice of that gender for the chosen language it is used
 * directly; otherwise the closest voice is re-pitched so it still *sounds*
 * male / female.
 */
enum class TtsVoiceGender(val label: String) {
    DEFAULT("Auto"),
    FEMALE("Female"),
    MALE("Male")
}

/**
 * Voice modulation presets — pitch + speaking-rate pairs the user picks from in
 * the language dialog. Applied on top of the gender choice.
 */
enum class TtsVoiceTone(val label: String, val pitch: Float, val rate: Float) {
    NORMAL("Normal", 1.0f, 0.95f),
    SOFT("Soft", 0.9f, 0.85f),
    DEEP("Deep", 0.72f, 0.9f),
    HIGH("High", 1.3f, 1.0f),
    CLEAR("Slow & clear", 1.0f, 0.72f)
}

object TextToSpeechHelper : TextToSpeech.OnInitListener {
    private const val TAG = "TTSHelper"
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Text asked to be spoken before the engine finished binding, or while a dead
     * engine was being recreated. Spoken as soon as init completes — this is what
     * fixes "the first sign after opening the chat made no sound".
     */
    private var pendingText: String? = null
    private var pendingLocale: Locale? = null

    /** Guards against a recreate → fail → recreate loop when the engine is truly broken. */
    private var retriedOnce = false

    /**
     * Controls the media stream so we can crank the phone's volume to maximum
     * right before speaking — this is the real lever that makes the voice loud
     * enough for the hearing person across the table.
     */
    private var audioManager: AudioManager? = null

    /**
     * When true (default), every [speak] first raises the media volume to the
     * device maximum so the other person hears the voice clearly. Toggle off if
     * the user finds it too loud.
     */
    var boostToMaxVolume: Boolean = true

    /** Desired voice language. Applied on init and whenever it changes. */
    private var desiredLocale: Locale = Locale.US

    // ── Soft-tone profile ──
    // Malayalam keeps its softer default (the user asked for a soft ml tone) —
    // but only while the tone preset is NORMAL; an explicit preset wins.
    private const val SOFT_PITCH_ML = 1.0f
    private const val SOFT_RATE_ML = 0.95f

    /** The user's voice style, pushed in from [LanguageConfig]. */
    var voiceGender: TtsVoiceGender = TtsVoiceGender.DEFAULT
        private set
    var voiceTone: TtsVoiceTone = TtsVoiceTone.NORMAL
        private set

    fun setVoiceStyle(gender: TtsVoiceGender, tone: TtsVoiceTone) {
        voiceGender = gender
        voiceTone = tone
        if (isInitialized) applyLanguage()
    }

    /**
     * Result of the most recent [TextToSpeech.setLanguage] call for
     * [desiredLocale]. Lets the UI warn when a language's voice data is missing.
     */
    var lastLanguageResult: Int = TextToSpeech.SUCCESS
        private set

    /**
     * Notified true while the engine is actually speaking and false when it
     * finishes — always on the main thread. The Conversation chat uses this to
     * pause speech-to-text during playback so the mic doesn't transcribe the
     * phone's own voice (echo).
     */
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
        if (audioManager == null) {
            audioManager = context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
    }

    /** Raise the media stream to its maximum so the spoken voice is as loud as
     *  the device allows. Best-effort — silently ignores any failure. */
    private fun raiseMediaVolume() {
        try {
            audioManager?.let { am ->
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < max) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not raise media volume: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            applyLanguage()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = notifySpeaking(true)
                override fun onDone(utteranceId: String?) = notifySpeaking(false)
                @Deprecated("deprecated in API level 21")
                override fun onError(utteranceId: String?) = notifySpeaking(false)
                override fun onError(utteranceId: String?, errorCode: Int) = notifySpeaking(false)
            })
            Log.d(TAG, "TTS initialized successfully.")
            // Anything asked for while the engine was still binding plays now.
            pendingText?.let { queued ->
                val locale = pendingLocale ?: desiredLocale
                pendingText = null
                pendingLocale = null
                speak(queued, locale)
            }
        } else {
            Log.e(TAG, "Initialization of TTS failed.")
            isInitialized = false
        }
    }

    /** Utterance callbacks arrive on a binder thread; hand them to the main thread. */
    private fun notifySpeaking(speaking: Boolean) {
        mainHandler.post { onSpeakingChanged?.invoke(speaking) }
    }

    /**
     * Choose the spoken language. Returns true when the voice is usable now;
     * false if the data is missing / unsupported (caller may want to warn). The
     * choice is remembered and re-applied if the engine finishes initializing
     * later.
     */
    fun setLanguage(locale: Locale): Boolean {
        desiredLocale = locale
        return if (isInitialized) applyLanguage() else true
    }

    private fun applyLanguage(locale: Locale = desiredLocale): Boolean {
        val engine = tts ?: return false
        val result = engine.setLanguage(locale)
        lastLanguageResult = result
        val ok = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ok) {
            Log.e(TAG, "Language $locale not supported or missing voice data.")
            // Never pronounce native text with an unrelated English voice.
            return false
        }
        applyVoiceStyle(engine, locale)
        return ok
    }

    /**
     * Apply the user's gender + tone choice: pick a matching installed voice when
     * one exists; otherwise keep the best voice and shift its pitch so the result
     * still reads as male / female. Tone presets stack on top.
     */
    private fun applyVoiceStyle(engine: TextToSpeech, locale: Locale) {
        val isMalayalam = locale.language.equals("ml", ignoreCase = true)
        var pitch = if (voiceTone == TtsVoiceTone.NORMAL && isMalayalam) SOFT_PITCH_ML else voiceTone.pitch
        var rate = if (voiceTone == TtsVoiceTone.NORMAL && isMalayalam) SOFT_RATE_ML else voiceTone.rate

        val genderMatched = selectVoice(engine, locale, voiceGender)
        
        // Ensure male / female pitch shift is applied distinctly regardless of whether a voice match was found
        pitch *= when (voiceGender) {
            TtsVoiceGender.MALE -> {
                rate *= 0.92f
                if (genderMatched) 0.82f else 0.65f
            }
            TtsVoiceGender.FEMALE -> {
                if (genderMatched) 1.05f else 1.18f
            }
            TtsVoiceGender.DEFAULT -> 1.0f
        }

        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * Apply the user's gender + tone to a SEPARATE engine (the gesture-audio
     * exporter runs its own TTS so exporting never interrupts the live voice),
     * keeping exported clips identical to what the chat speaks.
     */
    fun styleExternalEngine(engine: TextToSpeech, locale: Locale) = applyVoiceStyle(engine, locale)

    /** Best-effort gender read from a voice's name and features. */
    private fun guessGender(v: Voice): TtsVoiceGender? {
        val n = v.name.lowercase(Locale.US)
        val feat = v.features?.joinToString(" ")?.lowercase(Locale.US) ?: ""
        return when {
            "female" in n || "-fem-" in n || "_fem_" in n || "#female" in n || "mlf" in n || "-sfg-" in n || "-tpf-" in n || "female" in feat -> TtsVoiceGender.FEMALE
            "male" in n || "-mal-" in n || "_mal_" in n || "#male" in n || "mlm" in n || "-md-" in n || "-iob-" in n || "-iom-" in n || "male" in feat -> TtsVoiceGender.MALE
            else -> null
        }
    }

    /**
     * Pick the best installed voice for [locale] — preferring one whose
     * name or parameters match [gender].
     */
    private fun selectVoice(engine: TextToSpeech, locale: Locale, gender: TtsVoiceGender): Boolean {
        try {
            val voices: Set<Voice> = engine.voices ?: return false
            val matchingLocale = voices.filter { v ->
                v.locale?.language.equals(locale.language, ignoreCase = true)
            }
            if (matchingLocale.isEmpty()) return false

            val candidates = matchingLocale.filter { v ->
                !v.isNetworkConnectionRequired &&
                    v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }.ifEmpty { matchingLocale }

            if (gender != TtsVoiceGender.DEFAULT) {
                val match = candidates.filter { guessGender(it) == gender }.maxByOrNull { it.quality }
                    ?: matchingLocale.filter { guessGender(it) == gender }.maxByOrNull { it.quality }
                if (match != null) {
                    engine.voice = match
                    Log.d(TAG, "Selected ${gender.label} voice ${match.name} for ${locale.language}")
                    return true
                }
            }
            val best = candidates.maxByOrNull { it.quality }
            if (best != null) {
                engine.voice = best
                Log.d(TAG, "Selected fallback voice ${best.name} for ${locale.language}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice selection failed: ${e.message}")
        }
        return false
    }

    /**
     * Gesture labels arrive in ALL CAPS ("THIS IS GESTALK") and TTS engines read
     * unknown all-caps Latin words as acronyms — spelling them letter by letter
     * ("G-E-S-T-A-L-K"), worst with Indic voices. Lowercasing multi-letter caps
     * words makes every engine pronounce them as normal words. Single letters are
     * kept as-is so alphabet gestures ("A", "B") are still spoken as letters, and
     * non-Latin scripts (Malayalam, Tamil, …) have no case so they pass through.
     * Public because the gesture-audio exporter must speak identically.
     */
    fun toSpeakable(text: String): String =
        text.split(' ').joinToString(" ") { word ->
            val isAllCapsLatin = word.length >= 2 &&
                word.all { it in 'A'..'Z' }
            if (isAllCapsLatin) word.lowercase(Locale.US) else word
        }

    fun speak(text: String, locale: Locale = desiredLocale) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val engine = tts
        if (!isInitialized || engine == null) {
            // Engine still binding (or was shut down) — remember the text and make
            // sure an init is in flight; onInit will play it. Never drop audio.
            Log.w(TAG, "TTS not ready — queueing utterance.")
            pendingText = trimmed
            pendingLocale = locale
            appContext?.let { init(it) }
            return
        }
        if (!applyLanguage(locale)) {
            appContext?.let { context -> mainHandler.post {
                android.widget.Toast.makeText(context, "Install ${locale.displayLanguage} voice data in your phone's text-to-speech settings.", android.widget.Toast.LENGTH_LONG).show()
            } }
            notifySpeaking(false)
            return
        }
        if (boostToMaxVolume) raiseMediaVolume()
        // Play on the music stream at full relative volume so nothing else
        // (a low notification-stream level, per-utterance attenuation) quietens
        // the voice below the maxed-out media level.
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = engine.speak(
            toSpeakable(trimmed), TextToSpeech.QUEUE_FLUSH, params,
            "UtteranceId_${System.currentTimeMillis()}"
        )
        if (result == TextToSpeech.SUCCESS) {
            retriedOnce = false
        } else if (!retriedOnce) {
            // The engine process died (happens after long background stints) —
            // rebuild it once and replay this utterance so the voice still comes.
            Log.e(TAG, "speak() failed ($result) — recreating engine and retrying.")
            retriedOnce = true
            pendingText = trimmed
            pendingLocale = locale
            recreateEngine()
        } else {
            Log.e(TAG, "speak() failed again ($result) — giving up on this utterance.")
        }
    }

    private fun recreateEngine() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown during recreate failed: ${e.message}")
        }
        tts = null
        isInitialized = false
        appContext?.let { tts = TextToSpeech(it, this) }
    }

    fun shutdown() {
        pendingText = null
        pendingLocale = null
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
