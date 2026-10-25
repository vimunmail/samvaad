package com.example.xgestalkble.utils

import java.util.Locale

/** Whole-phrase lookups only: translating word fragments can reverse the intended meaning. */
internal object PhraseTranslations {
    data class Speech(val text: String, val language: String)

    /** Missing translations still speak the model's original label in its source language. */
    fun speech(text: String, language: String, labels: Map<String, Map<String, String>>): Speech {
        val translated = translate(text, language, labels)
        if (!translated.equals(text, ignoreCase = true)) return Speech(translated, language)
        val phrase = normalize(text)
        labels.entries.firstOrNull { normalize(it.key) == phrase }?.value?.get(language)
            ?.takeIf { valid(it) }?.let { return Speech(it, language) }
        if (labels.keys.any { normalize(it) == phrase }) return Speech(text, "en")
        val sources = labels.values.flatMap { values ->
            values.filterValues { valid(it) && normalize(it) == phrase }.keys
        }
        return Speech(text, if (language in sources) language else sources.firstOrNull() ?: "en")
    }

    fun normalize(text: String): String {
        var phrase = text.trim().uppercase(Locale.ROOT)
            .replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ")
            .trimEnd('?', '!', '.', ',', ' ', '।', '؟')
        // Spelling variants recorded in the introduction models; keep whole-phrase lookups.
        if (phrase.startsWith("IAM ")) phrase = "I AM " + phrase.removePrefix("IAM ")
        if (phrase.startsWith("IM FROM ")) phrase = "I AM FROM " + phrase.removePrefix("IM FROM ")
        if (phrase == "I FROM KERALA") phrase = "I AM FROM KERALA"
        if (phrase.startsWith("I CAN TRANSLATE INTO ") || phrase.startsWith("CAN TRANSLATE INTO ") || phrase.startsWith("TRANSLATE INTO ")) {
            phrase = phrase.replace(Regex("\\bTEXTV\\b"), "TEXT").replace(Regex("\\bIMAGES\\b"), "IMAGE")
        }
        val digit = listOf("ZERO", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE").indexOf(phrase)
        return if (digit >= 0) digit.toString() else phrase
    }
    private fun valid(text: String?) = !text.isNullOrBlank() && !Regex("<ctrl\\d+>|<unk>", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun translate(text: String, language: String, labels: Map<String, Map<String, String>>): String {
        val phrase = normalize(text)
        val direct = labels.entries.firstOrNull { normalize(it.key) == phrase }
        if (direct != null) {
            if (language == "en") return text
            return direct.value[language]?.takeIf { valid(it) } ?: text
        }
        val candidates = labels.filter { (_, values) -> values.values.any { valid(it) && normalize(it) == phrase } }
        val targets = candidates.map { (key, values) -> if (language == "en") key else values[language] }
        if (targets.isEmpty() || targets.any { !valid(it) } || targets.map { normalize(it!!) }.toSet().size != 1) return text
        return targets.first()!!
    }
}
