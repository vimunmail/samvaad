package com.example.xgestalkble.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper around Android's [SpeechRecognizer] that gives the Conversation
 * chat "continuous" listening: when the recognizer finishes a phrase (or times
 * out) it auto-restarts so the hearing person can keep talking without re-tapping
 * the mic.
 *
 * ONLINE-FIRST for maximum pickup: the online engine hears quiet, distant, and
 * crowd-masked speech far better than any on-device pack, so every language
 * starts online in the chosen language.
 *  - If the network is unreachable it falls back to OFFLINE recognition KEEPING
 *    the language (Android 13+ uses [SpeechRecognizer.createOnDeviceSpeechRecognizer],
 *    otherwise EXTRA_PREFER_OFFLINE) — English works offline on most phones.
 *  - Only if that also fails does it drop to the device-default language.
 *  - Gesture translation + TTS remain fully offline regardless.
 *
 * Lifecycle: create once per chat screen, call [start] / [stop], and always
 * [destroy] on dispose. All recognizer calls run on the main thread (required).
 */
class SpeechToTextHelper(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** True while we want the mic to stay on (survives auto-restarts). */
    private var active = false

    /**
     * BCP-47 language the hearing person speaks (e.g. "ml-IN", "en-US"). When
     * null the device default is used. Safe to change between phrases; the next
     * restart picks it up.
     */
    var languageTag: String? = null

    // ── Fallback ladder ──
    // ONLINE first for EVERY language (including English): the online engine is
    // dramatically better at picking up quiet, distant, or crowd-masked speech
    // than the on-device packs — this matters at ≥1 m in a noisy room. If the
    // network is unreachable we drop to offline (English-only on most phones),
    // then to the device-default language as a last resort.
    private var preferOffline = false        // online first (best accuracy in noise)
    private var triedOfflineFallback = false // offline rung used once per session
    private var useExplicitLanguage = true   // honor languageTag first
    private var sawAnyResult = false         // proves the current config works
    private var failCount = 0                // consecutive dead-config failures (for backoff)
    private var networkErrorStreak = 0       // network drops mid-session → step to offline

    /** Latest partial transcript (live preview while the user is speaking). */
    var onPartial: ((String) -> Unit)? = null
    /** A finalized utterance — push this into the chat as an incoming message. */
    var onFinal: ((String) -> Unit)? = null
    /** Listening on/off, for UI state. */
    var onListeningChanged: ((Boolean) -> Unit)? = null
    /** A non-recoverable error (recognition unavailable, permission, no pack). */
    var onError: ((String) -> Unit)? = null
    /** A non-fatal notice (e.g. stepped down to the device default language). */
    var onInfo: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** True when the platform can recognize speech fully on-device (API 33+). */
    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    // ── Pin GOOGLE's recognizer ──
    // The default RecognitionService on many phones (Samsung, Xiaomi, …) is the
    // vendor's own engine, which barely understands Malayalam and other Indian
    // languages. Google's service recognizes all of them well, so when it's
    // installed we bind to it EXPLICITLY instead of trusting the default.
    // (The manifest's <queries> for android.speech.RecognitionService makes this
    // lookup work on Android 11+.)
    private val googleRecognizerService: ComponentName? by lazy {
        val standardComponents = listOf(
            ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.service.SpeechRecognitionService"),
            ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService")
        )
        for (comp in standardComponents) {
            try {
                context.packageManager.getServiceInfo(comp, 0)
                Log.d(TAG, "Directly found Google recognition service component: ${comp.flattenToShortString()}")
                return@lazy comp
            } catch (_: Exception) {}
        }
        try {
            val services = context.packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE), 0
            )
            val preferredPackages = listOf(
                "com.google.android.googlequicksearchbox", // the Google app
                "com.google.android.tts"                   // Speech Recognition & Synthesis by Google
            )
            preferredPackages.firstNotNullOfOrNull { pkg ->
                services.firstOrNull { it.serviceInfo?.packageName == pkg }
            }?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
        } catch (e: Exception) {
            Log.w(TAG, "Recognition service lookup failed: ${e.message}")
            null
        }
    }

    /** Set when the pinned Google service keeps failing — fall back to default. */
    private var avoidPinnedService = false
    private var clientErrorStreak = 0

    /**
     * Live partial transcript of the CURRENT phrase. Partials are cumulative
     * within a session, so this is the fullest text the engine has heard. Used
     * to rescue speech whenever the final result arrives TRUNCATED (engine
     * returns only the tail of the sentence) or never arrives at all
     * (ERROR_NO_MATCH after visible partials).
     */
    private var lastPartial = ""

    /** Compare ignoring whitespace and invisible joiners: Malayalam finals and
     *  partials differ in ZWJ/ZWNJ (chillu forms), which silently breaks a plain
     *  contains() check and let truncated finals through. */
    private fun normalizeForCompare(s: String): String =
        s.filterNot {
            // U+200B zero-width space, U+200C ZWNJ (Malayalam chillu), U+200D ZWJ
            it.isWhitespace() || it.code in 0x200B..0x200D
        }

    /**
     * True when [piece] is a truncated fragment of the fuller [full] text the
     * partials already showed: either a literal substring, or dramatically
     * shorter (an engine rewrite keeps roughly the same length; a final half
     * the size of what was on screen means words were dropped).
     */
    private fun isTruncationOf(full: String, piece: String): Boolean {
        val nf = normalizeForCompare(full)
        val np = normalizeForCompare(piece)
        if (nf.length <= np.length + 2) return false
        return nf.contains(np) || nf.length >= np.length * 2
    }

    private fun createBestOnlineRecognizer(): SpeechRecognizer {
        val pinned = googleRecognizerService
        if (!avoidPinnedService && pinned != null) {
            try {
                Log.d(TAG, "Using pinned recognizer: ${pinned.flattenToShortString()}")
                return SpeechRecognizer.createSpeechRecognizer(context, pinned)
            } catch (e: Exception) {
                Log.w(TAG, "Pinned recognizer failed to create, using default: ${e.message}")
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun muteSystemSounds(mute: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val filterValue = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, filterValue, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, filterValue, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, mute)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, mute)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to adjust system volume: ${e.message}")
        }
    }

    // ── Always-on watchdog ──
    // The system recognizer sometimes dies silently: no result, no error, no
    // callback — and listening never resumes, so the mic "works for a while
    // then stops". A live session fires onRmsChanged continuously, so we track
    // the last callback time and force-restart whenever the recognizer goes
    // quiet for too long. This keeps the mic genuinely always-on.
    private var lastCallbackAt = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.elapsedRealtime() - lastCallbackAt > WATCHDOG_STALL_MS) {
                Log.w(TAG, "Recognizer went silent — force restarting")
                beginListening()
            }
            mainHandler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    /** Some devices leave the mic soft-muted (calls, other apps); force it live. */
    private fun ensureMicUnmuted() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isMicrophoneMute) audioManager.isMicrophoneMute = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unmute microphone: ${e.message}")
        }
    }

    fun start() {
        if (active) return
        if (!isAvailable()) {
            onError?.invoke("Speech recognition is not available on this device.")
            return
        }
        active = true
        ensureMicUnmuted()
        muteSystemSounds(true)
        // Fresh session: EVERY language starts ONLINE — the online engine hears
        // quiet / distant / crowd-masked speech far better than any on-device
        // pack (this is the "not recognising at one meter" fix). Offline is the
        // fallback rung when the network is unreachable, not the default.
        preferOffline = false
        triedOfflineFallback = false
        useExplicitLanguage = true
        sawAnyResult = false
        failCount = 0
        networkErrorStreak = 0
        clientErrorStreak = 0
        // Diagnostic for bad Malayalam/Indic transcription: if Google's recognizer
        // isn't installed we're stuck with the vendor engine — tell the user why.
        if (googleRecognizerService == null) {
            onInfo?.invoke(
                "Google speech service not found — using the phone's default recognizer. " +
                    "Install/enable the Google app for better recognition."
            )
        }
        onListeningChanged?.invoke(true)
        beginListening()
        // Start the stall watchdog for as long as the mic is meant to stay on.
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCHDOG_PERIOD_MS)
    }

    fun stop() {
        active = false
        // Don't lose a phrase caught mid-air: if the mic is turned off while
        // partials were on screen, commit them as the final message.
        val pending = lastPartial
        lastPartial = ""
        if (pending.isNotBlank()) onFinal?.invoke(pending)
        muteSystemSounds(false)
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.let {
            try {
                it.stopListening()
                it.cancel()
                it.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "stop() failed: ${e.message}")
            }
        }
        recognizer = null
        onListeningChanged?.invoke(false)
    }

    /** Fully release resources. Call from onDispose. */
    fun destroy() {
        stop()
    }

    private fun beginListening() {
        if (!active) return
        lastCallbackAt = SystemClock.elapsedRealtime() // fresh session — reset the stall clock
        lastPartial = "" // partials belong to one session; a fresh session starts clean
        try {
            // Recreate a fresh recognizer for each phrase — reusing one across
            // restarts is the main source of ERROR_RECOGNIZER_BUSY.
            recognizer?.destroy()
            recognizer = if (preferOffline && onDeviceAvailable()) {
                // Guaranteed on-device path (no network) — used while preferOffline holds.
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (e: Exception) {
                    Log.w(TAG, "createOnDeviceSpeechRecognizer failed, falling back to createSpeechRecognizer: ${e.message}")
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                // Online path: bind Google's recognizer explicitly when installed —
                // vendor default engines mangle Malayalam and other Indic languages.
                createBestOnlineRecognizer()
            }.apply { setRecognitionListener(listener) }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                // Pin the language while useExplicitLanguage holds; otherwise let the
                // recognizer use the device default (last-resort, still offline).
                if (useExplicitLanguage) {
                    val lang = languageTag ?: Locale.getDefault().toLanguageTag()
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // MAX-SENSITIVITY listening (deliberate — quiet, distant, or
                // crowd-masked speech): very long silence windows so soft or slow
                // speech is never cut mid-phrase, and a long minimum so each
                // session hangs on patiently instead of timing out early. Every
                // restart is a moment the mic can miss a word, so fewer + longer
                // sessions hear strictly more.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L)
                // NOTE: deliberately NO "android.speech.extra.DICTATION_MODE" and
                // NO EXTRA_SEGMENTED_SESSION here. Both were tried and REMOVED:
                // they change how the engine endpoints and commits text, and both
                // produced the "only the tail of the sentence appears" bug
                // ("where is your home" → "home"), worst in Malayalam. Classic
                // mode returns the WHOLE utterance in one final result — keep it.
                // Online first; the ladder flips this to offline when the network
                // is unreachable.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "beginListening failed: ${e.message}", e)
            relaxAndRestart()
        }
    }

    private fun scheduleRestart(delayMs: Long = 50) {
        if (!active) return
        // Small delay avoids hammering the service with back-to-back sessions.
        mainHandler.postDelayed({ beginListening() }, delayMs)
    }

    /**
     * Step down when the current config can't recognize anything:
     *   1. online → offline (KEEP the language — this is what keeps English
     *      working with no internet, since every phone ships an English pack),
     *   2. chosen language → device default,
     *   3. give up: surface a clear, actionable message + offer settings.
     * Then restart with the relaxed configuration.
     */
    private fun relaxAndRestart() {
        if (!active) return
        val isEnglish = languageTag?.lowercase()?.startsWith("en") == true
        when {
            isEnglish && !preferOffline && !triedOfflineFallback -> {
                // Online unreachable → try the on-device recognizer (keeps the
                // language; works for English everywhere, Indic where packs exist).
                preferOffline = true
                triedOfflineFallback = true
                onInfo?.invoke("No connection — switched to offline recognition (less accurate in noise).")
                scheduleRestart()
            }
            useExplicitLanguage && isEnglish -> {
                useExplicitLanguage = false
                onInfo?.invoke("That speech language is unavailable — using the device default language.")
                scheduleRestart()
            }
            else -> {
                // Keep retrying online in the selected language (Malayalam, etc. do not have offline packs).
                // Do NOT switch useExplicitLanguage to false for non-English languages.
                failCount++
                if (failCount == 1) {
                    onError?.invoke(
                        "Couldn't recognize speech — check your connection. Still listening… (tap for Voice input settings)"
                    )
                }
                scheduleRestart(1500)
            }
        }
    }

    /**
     * Open the system screen where offline voice / on-device speech packs are
     * downloaded. Wire this to the error message so the user can fix it in one tap.
     */
    fun openVoiceInputSettings() {
        val candidates = listOf(
            Intent("com.android.settings.action.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* try the next one */ }
        }
    }

    private val listener = object : RecognitionListener {
        // Every callback is a heartbeat — a live session fires onRmsChanged
        // constantly, so the watchdog only triggers when the recognizer is
        // genuinely dead and listening would otherwise silently stop.
        private fun heartbeat() { lastCallbackAt = SystemClock.elapsedRealtime() }

        override fun onReadyForSpeech(params: Bundle?) = heartbeat()
        override fun onBeginningOfSpeech() = heartbeat()
        override fun onRmsChanged(rmsdB: Float) = heartbeat()
        override fun onBufferReceived(buffer: ByteArray?) = heartbeat()
        override fun onEndOfSpeech() = heartbeat()

        override fun onPartialResults(partialResults: Bundle?) {
            heartbeat()
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                // A live partial proves this config hears speech — in a noisy room
                // finals can be rare, so don't let a later blip relax the ladder.
                sawAnyResult = true
                networkErrorStreak = 0
                clientErrorStreak = 0
                // Keep the FULLEST text seen for this phrase — a shorter partial
                // can follow a longer one while the engine re-scores.
                if (text.length >= lastPartial.length) lastPartial = text
                onPartial?.invoke(text)
            }
        }

        override fun onResults(results: Bundle?) {
            heartbeat()
            deliverFinal(results)
            if (active) {
                scheduleRestart()
            } else {
                stop()
            }
        }

        private fun deliverFinal(results: Bundle?) {
            val recognized = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            val partial = lastPartial
            lastPartial = ""
            // Trust the live partial over the final when the final is clearly a
            // truncated piece of what we already heard ("where is your home" was
            // on screen, the final came back as just "home"). A final that
            // REWRITES the text (better language-model pass) is kept as-is.
            val text = when {
                recognized.isNullOrBlank() -> partial
                isTruncationOf(full = partial, piece = recognized) -> partial
                else -> recognized
            }
            if (text.isNotBlank()) {
                sawAnyResult = true      // current config works; stop relaxing it
                failCount = 0            // recovered
                networkErrorStreak = 0   // connection is fine again
                clientErrorStreak = 0
                onFinal?.invoke(text)
            }
        }

        override fun onError(error: Int) {
            heartbeat()
            Log.d(TAG, "SpeechRecognizer onError: $error")
            // The engine sometimes ends a phrase with NO_MATCH / TIMEOUT even
            // though its partials were showing perfectly good text — commit that
            // text instead of throwing the whole sentence away.
            if ((error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                lastPartial.isNotBlank()
            ) {
                val rescued = lastPartial
                lastPartial = ""
                sawAnyResult = true
                failCount = 0
                onFinal?.invoke(rescued)
            }
            // Integer literals for language errors (named constants are API 33+):
            //   12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE
            //   14 = ERROR_CANNOT_CHECK_SUPPORT
            val languageError = error == 12 || error == 13 || error == 14
            val networkError = error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER
            if (networkError) networkErrorStreak++
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                clientErrorStreak++
            }
            when {
                // Permission is fatal — surface and stop.
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    active = false
                    onListeningChanged?.invoke(false)
                    onError?.invoke("Microphone permission is required for voice-to-text.")
                    muteSystemSounds(false)
                }

                // The chosen language isn't recognizable — step down the ladder.
                languageError -> relaxAndRestart()

                // Only GENUINE "can't reach the recognizer" errors step the ladder
                // down: immediately before the first result, or after 3 in a row
                // mid-conversation (walked out of coverage) — a single blip must
                // NOT relax, that's what made Malayalam jump to English on a
                // hiccup. CLIENT / AUDIO / BUSY are transient; just restart them.
                networkError && (!sawAnyResult || networkErrorStreak >= 3) ->
                    relaxAndRestart()

                // The pinned Google service keeps dying before ever hearing a
                // word (broken/disabled Google app) — unpin it and let the
                // system-default recognizer take over, BUT only if we are NOT on
                // Malayalam (since vendor engines do not support Malayalam).
                !sawAnyResult && clientErrorStreak >= 3 &&
                    !avoidPinnedService && googleRecognizerService != null &&
                    (languageTag?.lowercase()?.startsWith("ml") != true) -> {
                    Log.w(TAG, "Pinned Google recognizer keeps failing — using system default.")
                    avoidPinnedService = true
                    clientErrorStreak = 0
                    scheduleRestart()
                }

                // Everything else (silence, no-match, timeout, busy, client, audio) —
                // just keep listening so the mic stays continuously on.
                else -> {
                    if (active) {
                        scheduleRestart()
                    } else {
                        stop()
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val TAG = "SpeechToTextHelper"
        // Aggressive revive: a LIVE session fires onRmsChanged many times per
        // second, so 4 s with zero callbacks means the recognizer is truly dead —
        // restart fast so the "always on" mic has the shortest possible outage.
        private const val WATCHDOG_PERIOD_MS = 1500L  // how often the stall check runs
        private const val WATCHDOG_STALL_MS = 4000L   // silence this long ⇒ recognizer is dead
    }
}
