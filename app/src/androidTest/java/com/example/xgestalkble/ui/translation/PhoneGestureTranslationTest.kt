package com.example.xgestalkble.ui.translation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xgestalkble.utils.ChatLanguage
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.PhoneGestureTranslation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.example.xgestalkble.ui.conversation.GestureAudioExporter
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneGestureTranslationTest {
    @Test fun malayalamTranslationCanBeSavedAsRegionalAudio() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CompletableDeferred<Boolean>()
        val voiceEngine = withContext(Dispatchers.Main) { TextToSpeech(context) { ready.complete(it == TextToSpeech.SUCCESS) } }
        try {
            assertTrue(withTimeout(20_000) { ready.await() })
            assertTrue("Malayalam needs an installed offline voice.", voiceEngine.voices.orEmpty().any {
                it.locale.language == "ml" && !it.isNetworkConnectionRequired &&
                    it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            })
        } finally { voiceEngine.shutdown() }
        val clip = withTimeout(60_000) {
            GestureAudioExporter.synthesize(context, "നീ എന്തുകൊണ്ട് വന്നില്ല?", ChatLanguage.MALAYALAM.locale)
        }
        assertNotNull("Install Malayalam voice data on this phone to produce audio.", clip)
        assertTrue(clip!!.length() > 100)
        Log.i("OfflineTranslationTest", "Malayalam audio synthesized successfully (${clip.length()} bytes)")
    }
    @Test fun bundledModelTranslatesNewSentencesWithoutPairingOrNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GestureTranslator.ensureLoaded(context)
        // Force real phone inference, bypassing bundled phrase text and saved translations.
        for (source in listOf("why didn't you come", "why you are crying", "I do not want water.")) {
            val translated = GestureTranslator.translateText(source, ChatLanguage.MALAYALAM, testPhone = true)
            assertTrue(PhoneGestureTranslation.hasRegionalScript(translated, "ml"))
            assertFalse(translated.contains("<"))
            if (source == "I do not want water.") assertTrue(translated.contains("വേണ്ട") || translated.contains("ആവശ്യമില്ല"))
            Log.i("OfflineTranslationTest", "$source -> $translated")
        }
        for (language in ChatLanguage.entries.filterNot { it.isIdentity }) {
            val translated = PhoneGestureTranslation.translate(context, listOf("I need water."), language).single()
            assertTrue("${language.code}: $translated", PhoneGestureTranslation.hasRegionalScript(translated, language.code))
            Log.i("OfflineTranslationTest", "${language.code}: $translated")
        }
    }
}
