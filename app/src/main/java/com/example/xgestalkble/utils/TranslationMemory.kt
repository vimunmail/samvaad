package com.example.xgestalkble.utils

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SavedTranslation(val label: String, val language: String, val text: String, val reviewed: Boolean)

/** Complete sentences only. Reviewed meanings always win over later machine results. */
internal class TranslationMemory {
    private val entries = ConcurrentHashMap<Pair<String, String>, SavedTranslation>()
    private val locks = ConcurrentHashMap<Pair<String, String>, Mutex>()
    private val versions = ConcurrentHashMap<String, Int>()
    private fun key(label: String, language: String) = PhraseTranslations.normalize(label) to language
    fun get(label: String, language: String) = entries[key(label, language)]
    fun snapshot(): List<SavedTranslation> = entries.values.toList()
    fun version(label: String) = versions[PhraseTranslations.normalize(label)] ?: 0
    @Synchronized fun remove(label: String) {
        val normalized = PhraseTranslations.normalize(label)
        versions[normalized] = version(label) + 1
        entries.keys.removeAll { it.first == normalized }
    }
    @Synchronized fun put(entry: SavedTranslation, expectedVersion: Int? = null): Boolean {
        require(entry.text.isNotBlank() && !Regex("<ctrl\\d+>|<unk>", RegexOption.IGNORE_CASE).containsMatchIn(entry.text))
        if (expectedVersion != null && version(entry.label) != expectedVersion) return false
        entries.compute(key(entry.label, entry.language)) { _, old ->
            if (old?.reviewed == true && !entry.reviewed) old else entry.copy(text = entry.text.trim())
        }
        return true
    }
    suspend fun resolve(label: String, language: String, provider: suspend () -> String): SavedTranslation {
        get(label, language)?.let { return it }
        return locks.getOrPut(key(label, language)) { Mutex() }.withLock {
            get(label, language) ?: run {
                val version = version(label)
                val generated = SavedTranslation(label, language, provider(), false)
                check(put(generated, version)) { "The English meaning changed. Generate this translation again." }
                checkNotNull(get(label, language))
            }
        }
    }
}
