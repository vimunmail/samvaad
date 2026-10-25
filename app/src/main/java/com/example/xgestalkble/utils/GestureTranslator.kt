package com.example.xgestalkble.utils

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Bundled meanings, reviewed corrections and saved whole-sentence translations. */
object GestureTranslator {
    private val table = ConcurrentHashMap<String, Map<String, String>>()
    private val memory = TranslationMemory()
    private val meanings = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceMutex = Mutex()
    private val generationMutex = Mutex()
    private var context: Context? = null
    private var loaded = false
    private var speechJob: Job? = null
    private var backgroundPreparation: Job? = null
    private var speechLabel: Pair<String, ChatLanguage>? = null
    private var speechRetryAfter = 0L
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()
    private val mutableStatus = MutableStateFlow("")
    val status = mutableStatus.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    @Synchronized
    fun ensureLoaded(context: Context) {
        this.context = context.applicationContext
        if (loaded) return
        try {
            val labels = JSONObject(context.assets.open("translations/gesture_labels.json").bufferedReader().use { it.readText() })
                .getJSONObject("labels")
            labels.keys().forEach { label ->
                val values = labels.getJSONObject(label)
                table[normalize(label)] = values.keys().asSequence().associateWith { values.getString(it) }
            }
            val file = savedFile()
            if (file.baseFile.exists()) {
                try {
                    val saved = JSONObject(file.openRead().bufferedReader().use { it.readText() })
                    saved.optJSONObject("meanings")?.let { values -> values.keys().forEach { meanings[it] = values.getString(it) } }
                    val entries = saved.optJSONArray("translations") ?: JSONArray()
                    for (index in 0 until entries.length()) {
                        val entry = entries.getJSONObject(index)
                        if (ChatLanguage.entries.any { it.code == entry.optString("language") }) {
                            memory.put(SavedTranslation(entry.getString("label"), entry.getString("language"),
                                entry.getString("text"), entry.optBoolean("reviewed")))
                        }
                    }
                } catch (error: Exception) {
                    Log.e("GestureTranslator", "Saved translation file could not be read", error)
                    mutableError.value = "Some saved translations could not be read. Review or generate them again."
                }
            }
            loaded = true
        } catch (error: Exception) { Log.e("GestureTranslator", "Translation catalogue could not be read", error) }
    }

    private fun savedFile() = AtomicFile(File(checkNotNull(context).filesDir, "gesture-translations.json"))
    var automaticTranslation: Boolean
        get() = context?.getSharedPreferences("GestureTranslation", Context.MODE_PRIVATE)?.getBoolean("automatic", true) ?: true
        set(value) { context?.getSharedPreferences("GestureTranslation", Context.MODE_PRIVATE)?.edit()?.putBoolean("automatic", value)?.apply() }

    var useComputer: Boolean
        get() = context?.getSharedPreferences("GestureTranslation", Context.MODE_PRIVATE)?.getBoolean("computer", true) ?: true
        set(value) { context?.getSharedPreferences("GestureTranslation", Context.MODE_PRIVATE)?.edit()?.putBoolean("computer", value)?.apply() }

    // Existing installs also move to the bundled phone model; no old pairing is required.
    var usePhone: Boolean
        get() = context?.getSharedPreferences("GestureTranslation", Context.MODE_PRIVATE)?.getBoolean("phone", true) ?: true
        set(value) { context?.getSharedPreferences("GestureTranslation", Context.MODE_PRIVATE)?.edit()?.putBoolean("phone", value)?.apply() }

    private suspend fun generate(app: Context, texts: List<String>, language: ChatLanguage): List<String> =
        if (usePhone) PhoneGestureTranslation.translate(app, texts, language)
        else if (useComputer) ComputerGestureTranslation.translate(app, texts, language)
        else CloudGestureTranslation.translate(app, texts, language)

    fun normalize(label: String) = PhraseTranslations.normalize(label)
    fun englishMeaning(label: String): String = meanings[normalize(label)] ?: label.trim().replace('_', ' ')
    fun savedTranslation(label: String, language: ChatLanguage): String? = memory.get(label, language.code)?.text
        ?: PhraseTranslations.speech(englishMeaning(label), language.code, table).takeIf { it.language == language.code }?.text
    fun translationStatus(label: String, language: ChatLanguage): String = memory.get(label, language.code)?.let {
        if (it.reviewed) "Reviewed and saved" else "Automatic translation — check the meaning"
    } ?: if (savedTranslation(label, language) != null) "Bundled translation" else "Translation needed"
    fun translate(label: String, language: ChatLanguage): String =
        if (language.isIdentity) englishMeaning(label) else savedTranslation(label, language) ?: label

    private fun englishInput(label: String): String {
        val english = PhraseTranslations.translate(englishMeaning(label), "en", table)
        require(english.isNotBlank() && english.codePointCount(0, english.length) <= 300) { "Use a complete English phrase of at most 300 characters." }
        require(english.filter { it.isLetter() }.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }) {
            "Enter the English meaning of this sign before generating translations."
        }
        // Preserve names and acronyms such as ATM rather than changing their meaning.
        return english
    }

    suspend fun translateComplete(label: String, language: ChatLanguage): String {
        if (language.isIdentity) return englishMeaning(label)
        savedTranslation(label, language)?.let { return it }
        check(automaticTranslation) { "Automatic translation is off. Add this meaning in Model translations." }
        val app = checkNotNull(context) { "Translation is not initialized." }
        val result = memory.resolve(label, language.code) {
            generate(app, listOf(englishInput(label)), language).single()
        }
        persist()
        mutableRevision.update { it + 1 }
        return result.text
    }

    /** Plain text keeps its own wording and does not use a model label's edited meaning. */
    suspend fun translateText(english: String, language: ChatLanguage, testComputer: Boolean = false, testPhone: Boolean = false): String {
        val source = english.trim()
        require(source.isNotBlank() && source.codePointCount(0, source.length) <= 300) {
            "Enter a complete English meaning of at most 300 characters."
        }
        require(source.filter(Char::isLetter).all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }) {
            "Enter English in the English text field. You can edit regional text below."
        }
        if (language.isIdentity) return source
        val app = checkNotNull(context) { "Translation is not initialized." }
        // Testing always contacts IndicTrans2, even when the phone has a bundled meaning.
        if (testComputer) return ComputerGestureTranslation.translate(app, listOf(source), language).single()
        if (testPhone) return PhoneGestureTranslation.translate(app, listOf(source), language).single()
        PhraseTranslations.speech(source, language.code, table).takeIf { it.language == language.code }?.let { return it.text }
        val result = memory.resolve("Plain English: $source", language.code) {
            generate(app, listOf(source), language).single()
        }
        persist()
        return result.text
    }

    fun speak(label: String, language: ChatLanguage) {
        val request = normalize(label) to language
        if (speechJob?.isActive == true && speechLabel == request) return
        speechJob?.cancel()
        val hasSavedMeaning = language.isIdentity || savedTranslation(label, language) != null
        if (!hasSavedMeaning) {
            val app = context ?: return
            if (!usePhone && useComputer && !ComputerGestureTranslation.isConfigured(app)) {
                mutableStatus.value = "Set up the computer translator in Model translations before preparing new regional meanings."
                return
            }
            if (android.os.SystemClock.elapsedRealtime() < speechRetryAfter) return
        }
        speechLabel = request
        speechJob = scope.launch {
            try {
                val translated = translateComplete(label, language)
                speechRetryAfter = 0L
                mutableError.value = null
                if (LanguageConfig.voiceLanguage == language) TextToSpeechHelper.speak(translated, language.locale)
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // Recognition continues without repeated pop-ups or English fallback audio.
                speechRetryAfter = android.os.SystemClock.elapsedRealtime() + 30_000L
                mutableStatus.value = "Regional voice is waiting for a translation. Review the English meaning in Model translations."
            }
        }
    }

    /** Training and model loading prepare every regional language in the background. */
    fun registerModelClasses(classes: List<String>) {
        val app = context ?: return
        if (!usePhone && useComputer && !ComputerGestureTranslation.isConfigured(app)) {
            mutableStatus.value = "Set up the computer translator in Model translations before preparing new regional meanings."
            return
        }
        if (automaticTranslation) {
            backgroundPreparation?.cancel()
            backgroundPreparation = scope.launch { prepareTranslations(classes) }
        }
    }

    /** Prepare every regional language once, so switching voices needs no AI inference. */
    suspend fun prepareForConversation(labels: List<String>, language: ChatLanguage,
        onProgress: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        backgroundPreparation?.cancelAndJoin()
        backgroundPreparation = null
        val languages = (listOf(language) + ChatLanguage.entries).filterNot { it.isIdentity }.distinct()
        return prepareTranslations(labels, languages, onProgress)
    }

    suspend fun prepareTranslations(labels: List<String>, languages: List<ChatLanguage> = ChatLanguage.entries.filter { !it.isIdentity },
        onProgress: (Int, Int) -> Unit = { _, _ -> }): Boolean =
        generationMutex.withLock {
            mutableError.value = null
            val phrases = labels.filter { !GestureLabels.isRest(it) }.distinctBy(::normalize)
            val targets = languages.filterNot { it.isIdentity }.distinct()
            val total = phrases.size * targets.size
            var ready = targets.sumOf { language -> phrases.count { savedTranslation(it, language) != null } }
            onProgress(ready, total)
            var completed = 0
            for (language in languages.filter { !it.isIdentity }.distinct().sortedBy { if (it == LanguageConfig.voiceLanguage) 0 else 1 }) {
                // Save each local result immediately; cloud batches stay within its input limit.
                for (batch in phrases.filter { savedTranslation(it, language) == null }.chunked(if (usePhone || useComputer) 1 else 16)) {
                    try {
                        mutableStatus.value = "Generating ${language.displayName} meanings…"
                        val versions = batch.associateWith(memory::version)
                        val translated = generate(checkNotNull(context), batch.map(::englishInput), language)
                        batch.zip(translated).forEach { (label, text) ->
                            check(memory.put(SavedTranslation(label, language.code, text, false), versions.getValue(label))) {
                                "The English meaning changed. Generate its translation again."
                            }
                        }
                        persist()
                        mutableRevision.update { it + 1 }
                        ready += batch.size
                        onProgress(ready, total)
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        mutableError.value = error.message ?: "Translation failed. Retry the missing languages."
                        mutableStatus.value = "Saved meanings are available. Review the English text and retry missing translations."
                        return@withLock false
                    }
                }
                completed++
                mutableStatus.value = "Prepared $completed of ${languages.count { !it.isIdentity }} languages"
            }
            mutableStatus.value = "Translations ready. Check their meanings and listen before using them."
            true
        }

    suspend fun saveReviewed(label: String, language: ChatLanguage, text: String) {
        require(!language.isIdentity) { "Edit the English meaning instead." }
        memory.put(SavedTranslation(label, language.code, text, true))
        persist()
        mutableRevision.update { it + 1 }
    }

    suspend fun saveEnglishMeaning(label: String, english: String) {
        require(english.isNotBlank() && english.codePointCount(0, english.length) <= 300)
        if (englishMeaning(label) != english.trim()) {
            meanings[normalize(label)] = english.trim()
            memory.remove(label)
            persist()
            mutableRevision.update { it + 1 }
        }
    }

    private suspend fun persist() = persistenceMutex.withLock {
        withContext(Dispatchers.IO) {
            val entries = JSONArray()
            memory.snapshot().forEach { entry -> entries.put(JSONObject().put("label", entry.label).put("language", entry.language)
                .put("text", entry.text).put("reviewed", entry.reviewed)) }
            val content = JSONObject().put("version", 1).put("meanings", JSONObject(meanings.toMap())).put("translations", entries)
            val file = savedFile()
            val output = file.startWrite()
            try { output.write(content.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
    }
}
