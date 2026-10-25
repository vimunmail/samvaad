package com.example.xgestalkble.ui.translation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xgestalkble.ui.conversation.ConversationSetupScreen
import com.example.xgestalkble.utils.ChatLanguage
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.LanguageConfig
import com.example.xgestalkble.utils.RecordingModelType
import java.io.File
import android.util.AtomicFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPreparationTest {
    @get:Rule val compose = createComposeRule()

    private fun checkSetup(label: String, ready: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLanguage = LanguageConfig.voiceLanguage
        val fixture = File(context.getExternalFilesDir("models"), "conversation-preparation-test-${System.nanoTime()}.json")
        fixture.writeText("""{"trees":[],"classes":["REST","$label"]}""")
        var opened = false
        try {
            LanguageConfig.setVoiceLanguage(context, ChatLanguage.MALAYALAM)
            compose.setContent { MaterialTheme {
                ConversationSetupScreen(RecordingModelType.RFC, {}, fixture.name, {},
                    onStart = { opened = true }, onHistory = {}, onBackClick = {})
            } }
            if (ready) {
                compose.waitUntil(15_000) { compose.onAllNodesWithText("All gesture meanings are ready in all 12 languages. You can switch languages during chat.").fetchSemanticsNodes().isNotEmpty() }
                ChatLanguage.entries.filterNot { it.isIdentity }.forEach { language ->
                    assertTrue("Missing ${language.displayName}", GestureTranslator.savedTranslation(label, language) != null)
                }
                // Switching the output language must preserve readiness for this model.
                compose.runOnIdle { LanguageConfig.setVoiceLanguage(context, ChatLanguage.TAMIL) }
                compose.onNodeWithText("OPEN CHAT").assertIsEnabled().performClick()
                assertTrue(opened)
            } else {
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Retry preparing translations").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("OPEN CHAT").assertIsNotEnabled()
                assertFalse(opened)
            }
        } finally {
            fixture.delete()
            LanguageConfig.setVoiceLanguage(context, originalLanguage)
        }
    }

    @Test fun cachedMeaningsAllowChatWithoutAnotherTranslation() = checkSetup("HELLO", true)
    @Test fun missingEnglishMeaningKeepsChatClosedForCorrection() = checkSetup("അപരിചിതമായ ഇംഗ്ലീഷ് അർത്ഥമില്ലാത്ത ചിഹ്നം", false)

    @Test fun newModelPhraseIsSavedInEveryLanguageAndReusedWhenSwitching() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GestureTranslator.ensureLoaded(context)
        val saved = AtomicFile(File(context.filesDir, "gesture-translations.json"))
        val original = if (saved.baseFile.exists()) saved.openRead().use { it.readBytes() } else null
        val phrase = "I need a quiet place to rest."
        val progress = mutableListOf<Pair<Int, Int>>()
        try {
            assertTrue(GestureTranslator.prepareForConversation(listOf("REST", phrase, phrase), ChatLanguage.MALAYALAM) { ready, total ->
                progress.add(ready to total)
            })
            assertEquals(12 to 12, progress.last())
            ChatLanguage.entries.filterNot { it.isIdentity }.forEach { language ->
                assertTrue("Missing ${language.displayName}", !GestureTranslator.savedTranslation(phrase, language).isNullOrBlank())
            }
            progress.clear()
            assertTrue(GestureTranslator.prepareForConversation(listOf(phrase), ChatLanguage.TAMIL) { ready, total ->
                progress.add(ready to total)
            })
            assertEquals(listOf(12 to 12), progress)
        } finally {
            if (original == null) saved.delete() else {
                val output = saved.startWrite()
                try { output.write(original); saved.finishWrite(output) }
                catch (error: Exception) { saved.failWrite(output); throw error }
            }
        }
    }
}
