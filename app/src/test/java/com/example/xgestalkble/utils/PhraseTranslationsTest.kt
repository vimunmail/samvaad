package com.example.xgestalkble.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseTranslationsTest {
    private val labels = mapOf("HELLO" to mapOf("hi" to "नमस्ते", "ml" to "നമസ്കാരം"),
        "I AM FROM KERALA" to mapOf("hi" to "मैं केरल से हूँ", "ml" to "ഞാൻ കേരളത്തിൽ നിന്നാണ്"))

    @Test fun unknownModelLabelsStillHaveEnglishSpeech() {
        assertEquals(PhraseTranslations.Speech("WHERE IS THE CHECKOUT", "en"),
            PhraseTranslations.speech("WHERE IS THE CHECKOUT", "ml", labels))
    }

    @Test fun missingAndInvalidTranslationsFallBackToEnglishSpeech() {
        assertEquals(PhraseTranslations.Speech("HELLO", "en"),
            PhraseTranslations.speech("HELLO", "as", labels))
        assertEquals(PhraseTranslations.Speech("HELLO", "en"),
            PhraseTranslations.speech("HELLO", "kn", mapOf("HELLO" to mapOf("kn" to "<ctrl42>"))))
    }

    @Test fun translatedSpeechAndAlreadyTranslatedLabelsUseMatchingVoices() {
        assertEquals(PhraseTranslations.Speech("നമസ്കാരം", "ml"),
            PhraseTranslations.speech("HELLO", "ml", labels))
        assertEquals(PhraseTranslations.Speech("നമസ്കാരം", "ml"),
            PhraseTranslations.speech("നമസ്കാരം", "ml", labels))
        assertEquals(PhraseTranslations.Speech("नमस्ते", "hi"),
            PhraseTranslations.speech("नमस्ते", "as", labels))
    }

    @Test fun completePhrasesTranslateAcrossSourceLanguages() {
        assertEquals("नमस्ते", PhraseTranslations.translate("Hello!", "hi", labels))
        assertEquals("नमस्ते", PhraseTranslations.translate("നമസ്കാരം", "hi", labels))
        assertEquals("നമസ്കാരം", PhraseTranslations.translate("नमस्ते", "ml", labels))
    }
    @Test fun explicitSameTextTranslationsStillUseTheSelectedVoice() {
        assertEquals(PhraseTranslations.Speech("Vimun", "ml"),
            PhraseTranslations.speech("Vimun", "ml", mapOf("VIMUN" to mapOf("ml" to "Vimun"))))
    }
    @Test fun pluralNounsInNewLabelsAreNotCollapsedIntoDifferentMeanings() {
        assertEquals("I NEED IMAGES", PhraseTranslations.normalize("I need images"))
        assertEquals("I NEED IMAGE", PhraseTranslations.normalize("I need image"))
    }
    @Test fun unknownSentencesAndNegationNeverBecomeInventedTranslations() {
        val original = "I do not live in Kerala"
        assertEquals(original, PhraseTranslations.translate(original, "ml", labels))
        assertEquals("Hello doctor", PhraseTranslations.translate("Hello doctor", "hi", labels))
    }
    @Test fun ambiguousNativePhrasesAndPlaceholderValuesStayOriginal() {
        val ambiguous = mapOf("TOMORROW" to mapOf("hi" to "कल", "ml" to "നാളെ"),
            "YESTERDAY" to mapOf("hi" to "कल", "ml" to "ഇന്നലെ"))
        assertEquals("कल", PhraseTranslations.translate("कल", "ml", ambiguous))
        assertEquals("Hello", PhraseTranslations.translate("Hello", "kn", mapOf("HELLO" to mapOf("kn" to "<ctrl42>"))))
    }
}
