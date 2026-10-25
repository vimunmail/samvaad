package com.example.xgestalkble.ui.translation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.xgestalkble.ui.sound.TextToAudioScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.xgestalkble.utils.LanguageConfig
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.example.xgestalkble.theme.XgestalkBLETheme

@RunWith(AndroidJUnit4::class)
class TextTranslationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishTextProducesEditableMalayalamBeforeSpeech() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLanguage = LanguageConfig.voiceLanguage
        try {
        compose.setContent { XgestalkBLETheme { TextToAudioScreen(onBackClick = {}) } }
        compose.onNodeWithContentDescription("Choose output language").performScrollTo().performClick()
        compose.onNodeWithText("Malayalam · മലയാളം").performClick()
        compose.onNode(hasSetTextAction() and hasText("1. English text")).performScrollTo().performTextInput("HELLO")
        compose.onNodeWithText("Translate to Malayalam").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText("നമസ്കാരം")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("നമസ്കാരം")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Save audio").assertIsEnabled()
        compose.onNodeWithText("Computer / model setup").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "translation-screen.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        } finally { LanguageConfig.setVoiceLanguage(context, originalLanguage) }
    }
}
