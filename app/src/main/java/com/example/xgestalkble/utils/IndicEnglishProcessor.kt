package com.example.xgestalkble.utils

import org.json.JSONArray
import org.json.JSONObject

/** Android port of the official English preprocessing and Indic output detokenization.
 * Regex tables and script mappings are exported from the attributed upstream libraries.
 */
internal class IndicEnglishProcessor(private val config: JSONObject, private val scripts: JSONObject) {
    data class Prepared(val text: String, val entities: Map<String, String>)
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun rules(name: String): List<Pair<Regex, String>> = config.getJSONArray(name).let { array ->
        (0 until array.length()).map {
            val pair = array.getJSONArray(it)
            val replacement = pair.getString(1).replace(Regex("\\\\g<(\\d+)>")) { m -> "$" + m.groupValues[1] }
                .replace(Regex("\\\\(\\d+)")) { m -> "$" + m.groupValues[1] }
            Regex(pair.getString(0)) to replacement
        }
    }
    private val normalizer = rules("normalizer")
    private val punctuation = rules("punctuationRegex")
    private val initial = rules("initial")
    private val afterDots = rules("afterDots")
    private val trailing = rules("trailing")
    private val detokenizer = rules("detokenizer")
    private val entityPatterns = config.getJSONArray("entities").strings().map(::Regex)
    private val variants = config.getJSONArray("entityVariants").strings()
    private val prefixes = config.getJSONArray("prefixes").strings().toSet()
    private val numericPrefixes = config.getJSONArray("numericPrefixes").strings().toSet()
    private fun apply(text: String, rules: List<Pair<Regex, String>>) = rules.fold(text) { value, (pattern, replacement) ->
        pattern.replace(value, replacement)
    }

    fun prepare(english: String): Prepared {
        var text = english.trim()
        val literals = config.getJSONArray("punctuationLiterals")
        for (i in 0 until literals.length()) {
            val pair = literals.getJSONArray(i)
            text = text.replace(pair.getString(0), pair.getString(1))
        }
        text = apply(text, punctuation).trim()
        val entities = linkedMapOf<String, String>()
        var serial = 1
        entityPatterns.forEachIndexed { patternIndex, pattern ->
            pattern.findAll(text).map { it.value }.distinct().toList().forEach { original ->
                val stripped = original.replace(Regex("[ .:]"), "")
                if ((patternIndex == 1 && original.replace(".", "").length < 4) ||
                    (patternIndex == 2 && stripped.length < 4)) return@forEach
                variants.forEach { entities[it.replace("{id}", serial.toString())] = original }
                text = text.replace(original, "<ID$serial>")
                serial++
            }
        }
        text = apply(apply(text, normalizer), initial).trim()
        text = Regex("\\.([.]+)").replace(text, " DOTMULTI$1")
        while (text.contains("DOTMULTI.")) {
            text = Regex("DOTMULTI\\.([^\\.])").replace(text, "DOTDOTMULTI $1")
            text = text.replace("DOTMULTI.", "DOTDOTMULTI")
        }
        text = apply(text, afterDots)
        val tokens = text.trim().split(Regex("\\s+"))
        text = tokens.mapIndexed { i, token ->
            if (token.length <= 1 || !token.endsWith('.')) token else {
                val prefix = token.dropLast(1)
                val next = tokens.getOrNull(i + 1).orEmpty()
                val keep = (prefix.contains('.') && prefix.any(Char::isLetter)) ||
                    (prefix in prefixes && prefix !in numericPrefixes) ||
                    next.firstOrNull()?.isLowerCase() == true ||
                    (prefix in numericPrefixes && next.firstOrNull()?.isDigit() == true)
                if (keep) token else "$prefix ."
            }
        }.joinToString(" ")
        text = apply(text, trailing).trim()
        while (text.contains("DOTDOTMULTI")) text = text.replace("DOTDOTMULTI", "DOTMULTI.")
        text = text.replace("DOTMULTI", ".")
        return Prepared(text, entities)
    }

    fun finish(output: String, language: String, entities: Map<String, String>): String {
        var text = output
        if (language == "ur") text = text.replace(" ؟", "؟").replace(" ۔", "۔").replace(" ،", "،").replace("ٮ۪", "ؠ")
        if (language == "or") text = text.replace("ଯ଼", "ୟ")
        entities.forEach { (placeholder, original) -> text = text.replace(placeholder, original) }
        val mapping = scripts.getJSONArray(language)
        text = text.map { if (it.code in 0x900..0x97f) mapping.getString(it.code - 0x900) else it.toString() }.joinToString("")
        text = Regex(config.getString("numbers")).replace(text) { it.value.replace(" ", "") }
        text = apply(text, detokenizer)
        for (quote in "'\"`") {
            var count = 0
            text = text.map { if (it == quote) { if (count++ % 2 == 0) "@RA" else "@LA" } else it.toString() }.joinToString("")
                .replace("@RA ", quote.toString()).replace(" @LA", quote.toString())
                .replace("@RA", quote.toString()).replace("@LA", quote.toString())
        }
        return text.trim()
    }
}
