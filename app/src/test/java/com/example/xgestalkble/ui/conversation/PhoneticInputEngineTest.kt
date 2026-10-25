package com.example.xgestalkble.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneticInputEngineTest {
    private val engine = PhoneticInputEngine(mapOf("ml" to PhoneticScript(
        vowels = mapOf("a" to ("അ" to ""), "aa" to ("ആ" to "ാ"), "i" to ("ഇ" to "ി"), "u" to ("ഉ" to "ു")),
        consonants = mapOf("k" to "ക", "v" to "വ", "m" to "മ", "r" to "ര"),
        signs = emptyMap(), virama = "്", terminal = mapOf("ര്" to "ർ", "മ്" to "ം"),
        words = mapOf("sughamano" to "സുഖമാണോ", "enikku" to "എനിക്ക്", "vellam" to "വെള്ളം", "venam" to "വേണം", "nanni" to "നന്ദി"),
        example = "sughamano"
    )))

    @Test fun commonManglishAndCapitalization() {
        assertEquals("സുഖമാണോ? എനിക്ക് വെള്ളം വേണം. നന്ദി!", engine.convert("Sughamano? enikku vellam venam. NANNI!", "ml"))
    }

    @Test fun unfamiliarWordsUseVowelMarksConjunctsAndFinalLetters() {
        assertEquals("കവി അമ്മ കാക കുമർ", engine.convert("kavi amma kaaka kumar", "ml"))
    }

    @Test fun nativeTextAndLiteralEnglishSurvive() {
        assertEquals("ഞാൻ 123 🙂\nനന്ദി Alex Smith https://example.com?q=hello alex@example.org",
            engine.convert("ഞാൻ 123 🙂\nnanni [[Alex Smith]] https://example.com?q=hello alex@example.org", "ml"))
    }

    @Test fun identityWhenLanguageIsUnavailable() {
        assertEquals("How are you?", engine.convert("How are you?", "en"))
        assertEquals("hello", engine.convert("hello", "missing"))
        assertEquals("", engine.convert("", "ml"))
    }
}
