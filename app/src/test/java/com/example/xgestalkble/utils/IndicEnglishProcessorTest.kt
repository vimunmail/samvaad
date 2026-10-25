package com.example.xgestalkble.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class IndicEnglishProcessorTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "local-translation/android-preprocessing-cases.json").exists() }
    private val vocab = JSONObject(File(root, "local-translation/.state/android-assets/indictrans2/vocab.json").readText())
    private val processor = IndicEnglishProcessor(vocab.getJSONObject("processing"), vocab.getJSONObject("scripts"))

    @Test fun androidNormalizationMatchesUpstreamEnglishWithNegationAndProtectedEntities() {
        val references = JSONArray(File(root, "local-translation/android-preprocessing-cases.json").readText())
        for (index in 0 until references.length()) {
            val reference = references.getJSONObject(index)
            assertEquals(reference.getString("english"), reference.getString("normalized"), processor.prepare(reference.getString("english")).text)
        }
    }

    @Test fun officialMalayalamScriptMappingPreservesNegationAndPunctuation() {
        assertEquals("എനിക്ക് വെള്ളം വേണ്ട.", processor.finish("ऎनिक्क् वॆळ्ळं वेण्ट .", "ml", emptyMap()))
        val prepared = processor.prepare("Email gestalk@example.com.")
        assertEquals("gestalk@example.com", prepared.entities["<ID1>"])
        assertEquals("gestalk@example.com", processor.finish("<ID1>", "ml", prepared.entities))
    }
}
