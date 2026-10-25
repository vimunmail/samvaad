package com.example.xgestalkble.ui.conversation

import java.util.Locale

internal data class PhoneticScript(
    val vowels: Map<String, Pair<String, String>>,
    val consonants: Map<String, String>,
    val signs: Map<String, String>,
    val virama: String,
    val terminal: Map<String, String>,
    val words: Map<String, String>,
    val example: String
) {
    val tokens = (vowels.keys + consonants.keys + signs.keys).sortedByDescending { it.length }
}

/** Same offline token rules as the guest web composer. Never translates meaning. */
internal class PhoneticInputEngine(val scripts: Map<String, PhoneticScript>) {
    private val chunks = Regex("\\[\\[[\\s\\S]*?\\]\\]|(?:https?://|www\\.)\\S+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|[A-Za-z]+(?:[~^][A-Za-z]+)*")
    fun convert(text: String, language: String): String {
        val script = scripts[language] ?: return text
        if (language == "en") return text
        return chunks.replace(text) { match ->
            val token = match.value
            when {
                token.startsWith("[[") -> token.substring(2, token.length - 2)
                token.startsWith("http://") || token.startsWith("https://") || token.startsWith("www.") || token.contains('@') -> token
                else -> word(token, script)
            }
        }
    }
    private fun word(word: String, script: PhoneticScript): String {
        script.words[word.lowercase(Locale.US)]?.let { return it }
        val value = if (word == word.uppercase(Locale.US) || Regex("^[A-Z][a-z]+$").matches(word)) word.lowercase(Locale.US) else word
        val result = StringBuilder()
        var consonant = false
        var offset = 0
        while (offset < value.length) {
            val token = script.tokens.firstOrNull { value.startsWith(it, offset) }
            if (token == null) {
                if (consonant) result.append(script.virama)
                result.append(value[offset++]); consonant = false
                continue
            }
            when {
                script.vowels.containsKey(token) -> {
                    val vowel = script.vowels.getValue(token)
                    result.append(if (consonant) vowel.second else vowel.first); consonant = false
                }
                script.consonants.containsKey(token) -> {
                    if (consonant) result.append(script.virama)
                    result.append(script.consonants.getValue(token)); consonant = true
                }
                else -> { result.append(script.signs.getValue(token)); consonant = false }
            }
            offset += token.length
        }
        if (consonant) result.append(script.virama)
        var output = result.toString()
        for ((ending, replacement) in script.terminal) {
            if (ending.isNotEmpty() && output.endsWith(ending)) {
                output = output.dropLast(ending.length) + replacement
                break
            }
        }
        return output
    }
}
