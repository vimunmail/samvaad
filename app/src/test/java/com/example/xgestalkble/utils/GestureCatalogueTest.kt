package com.example.xgestalkble.utils

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureCatalogueTest {
    private fun catalogue(): Map<String, Map<String, String>> {
        val labels = JSONObject(File("src/main/assets/translations/gesture_labels.json").readText()).getJSONObject("labels")
        return labels.keys().asSequence().associate { label ->
            val values = labels.getJSONObject(label)
            PhraseTranslations.normalize(label) to values.keys().asSequence().associateWith { values.getString(it) }
        }
    }

    // Exact labels from waveshare_intro and new intro waveshare on the user's phone.
    @Test fun everyWaveshareIntroductionSignSpeaksMalayalam() {
        val labels = catalogue()
        val modelLabels = listOf("Hello", "How are you ?", "I am fine",
            "I can translate into voice text and images", "I from kerala", "I want help",
            "Iam deaf and I can't speak", "Indian sign language translator", "My name is Vimun",
            "No", "Sorry", "Thankyou", "This is Gestalk", "Yes", "what is your name?")
        for (label in modelLabels) {
            val speech = PhraseTranslations.speech(label, "ml", labels)
            assertEquals("Voice language for $label", "ml", speech.language)
            assertTrue("Malayalam text for $label: ${speech.text}", speech.text.any { it in '\u0D00'..'\u0D7F' })
        }
    }

    @Test fun everyBundledModelSignHasMalayalamSpeech() {
        val labels = catalogue()
        for (file in File("src/main/assets/models").listFiles().orEmpty().filter { it.extension == "json" }) {
            val model = JSONObject(file.readText())
            val classes = model.optJSONArray("classes") ?: model.optJSONArray("labels") ?: continue
            for (index in 0 until classes.length()) {
                val label = classes.getString(index)
                if (label.equals("rest", ignoreCase = true)) continue
                val speech = PhraseTranslations.speech(label, "ml", labels)
                assertEquals("${file.name}: $label", "ml", speech.language)
                assertTrue("${file.name}: $label", speech.text.any { it in '\u0D00'..'\u0D7F' })
            }
        }
    }

    @Test fun spelledOutDigitsUseMalayalamNumberNames() {
        val labels = catalogue()
        for (label in listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine")) {
            assertEquals(label, "ml", PhraseTranslations.speech(label, "ml", labels).language)
        }
    }
}
