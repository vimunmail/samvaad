package com.example.xgestalkble.ui.translation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import com.example.xgestalkble.utils.LanguageConfig
import android.util.AtomicFile
import java.io.File

@RunWith(AndroidJUnit4::class)
class ModelTranslationsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newEnglishPhraseCanBeReviewedAndSavedInMalayalamWithoutNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLanguage = LanguageConfig.voiceLanguage
        val saved = AtomicFile(File(context.filesDir, "gesture-translations.json"))
        val original = if (saved.baseFile.exists()) saved.openRead().use { it.readBytes() } else null
        val modelsDir = context.getExternalFilesDir("models") ?: context.filesDir
        modelsDir.mkdirs()
        val fixture = File(modelsDir, "!translation-ui-test-${System.nanoTime()}.json")
        fixture.writeText("""{"classes":["I need a quiet place to rest."]}""")
        try {
        compose.setContent { MaterialTheme { ModelTranslationsScreen(onBackClick = {}) } }
        val phrase = "I need a quiet place to rest ${System.currentTimeMillis()}"
        compose.waitUntil(10_000) { compose.onAllNodesWithText(fixture.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("Try a new English phrase")).performScrollTo().performTextInput(phrase)
        compose.onNodeWithText("Add phrase for translation").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(phrase))
        compose.onAllNodesWithText("Review / correct").onLast().performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Check the sign’s meaning").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Check the sign’s meaning").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Malayalam meaning")).performScrollTo().performTextInput("എനിക്ക് വിശ്രമിക്കാൻ ശാന്തമായ ഒരു സ്ഥലം വേണം")
        compose.onNodeWithText("Save reviewed meaning").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reviewed and saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("എനിക്ക് വിശ്രമിക്കാൻ ശാന്തമായ ഒരു സ്ഥലം വേണം").assertIsDisplayed()
        } finally {
            fixture.delete()
            if (original == null) saved.delete() else {
                val output = saved.startWrite()
                try { output.write(original); saved.finishWrite(output) }
                catch (error: Exception) { saved.failWrite(output); throw error }
            }
            LanguageConfig.setVoiceLanguage(context, originalLanguage)
        }
    }
}
