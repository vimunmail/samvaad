package com.example.xgestalkble

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.xgestalkble.ui.collection.DataCollectionScreen
import com.example.xgestalkble.ui.collection.TeachGesturesScreen
import com.example.xgestalkble.ui.main.MainScreen
import com.example.xgestalkble.ui.main.ToolsScreen
import com.example.xgestalkble.ui.main.BootScreen
import com.example.xgestalkble.ui.monitor.SerialMonitorScreen
import com.example.xgestalkble.ui.prediction.PredictionScreen
import com.example.xgestalkble.ui.prediction.GestureToVoiceScreen
import com.example.xgestalkble.ui.conversation.ConversationScreen
import com.example.xgestalkble.ui.sound.SoundAlertScreen
import com.example.xgestalkble.ui.sound.TextToAudioScreen
import com.example.xgestalkble.ui.translation.ModelTranslationsScreen

@Composable
fun MainNavigation() {
  var isBooting by remember { mutableStateOf(true) }

  Crossfade(targetState = isBooting, label = "BootCrossfade") { booting ->
    if (booting) {
      BootScreen(onBootComplete = { isBooting = false })
    } else {
      val backStack = rememberNavBackStack(Main)

      NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(
            onItemClick = { navKey -> backStack.add(navKey) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Tools> {
          ToolsScreen(
            onItemClick = { navKey -> backStack.add(navKey) },
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<SerialMonitor> {
          SerialMonitorScreen(
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<DataCollection> {
          DataCollectionScreen(
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<TeachGestures> {
          TeachGesturesScreen(
            onBackClick = { backStack.removeLastOrNull() },
            onTryModel = { backStack.add(GestureToVoice) },
            onOpenAdvanced = { backStack.add(DataCollection) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Prediction> {
          PredictionScreen(
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<ModelTranslations> {
          ModelTranslationsScreen(onBackClick = { backStack.removeLastOrNull() }, modifier = Modifier.safeDrawingPadding())
        }
        entry<GestureToVoice> {
          GestureToVoiceScreen(
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Conversation> {
          ConversationScreen(
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<SoundAlert> {
          SoundAlertScreen(
            onBackClick = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<TextToAudio> {
          TextToAudioScreen(
            onBackClick = { backStack.removeLastOrNull() },
            onTranslationSetupClick = { backStack.add(ModelTranslations) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
      },
    )
  }
}

}
