package com.example.xgestalkble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.xgestalkble.ui.conversation.ConversationStore
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.xgestalkble.theme.ThemeConfig
import com.example.xgestalkble.theme.SamvaadTheme
import com.example.xgestalkble.ui.main.importManagedFiles
import com.example.xgestalkble.utils.BundledModels
import com.example.xgestalkble.utils.LanguageConfig
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.TextToSpeechHelper

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Restore the user's saved light/dark theme choice
    ThemeConfig.init(this)
    // Migrate existing saved plaintext transcripts even before history is opened.
    lifecycleScope.launch(Dispatchers.IO) { ConversationStore.list(applicationContext) }

    // Restore the user's saved chat language choices (sign→voice, speech→text)
    LanguageConfig.init(this)
    GestureTranslator.ensureLoaded(this)

    // Initialize TTS Engine for mute user assistance
    TextToSpeechHelper.init(this)

    // Copy APK-bundled models into the models folder (first install / new phone)
    BundledModels.seed(this)

    // Load glove calibration on boot
    val prefs = getSharedPreferences("GloveCalibration", MODE_PRIVATE)
    if (prefs.getBoolean("isCalibrated", false)) {
        val straight = FloatArray(5)
        val bent = FloatArray(5)
        for (i in 0 until 5) {
            straight[i] = prefs.getFloat("straight_$i", 0.0f)
            bent[i] = prefs.getFloat("bent_$i", 1.0f)
        }
        com.example.xgestalkble.utils.DataNormalizer.setCalibration(straight, bent)
    }

    // Android 13+: notifications need runtime consent. The glove-connection and
    // training foreground services use notifications to show live status/progress.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    // A model/CSV shared to Samvaad or opened with it lands here as the intent.
    handleImportIntent(intent)

    enableEdgeToEdge()
    setContent {
      SamvaadTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleImportIntent(intent)
  }

  /**
   * Import files that arrive via the share sheet ("Share → Samvaad") or
   * "Open with Samvaad": models (.json) go to models/, datasets (.csv) to
   * TelemetryData/. This is the reliable way to receive shared files — the
   * system document picker hides .json files from its Recent/Documents views,
   * so hunting for them there fails for most users.
   */
  private fun handleImportIntent(intent: Intent?) {
    val uris = mutableListOf<Uri>()
    @Suppress("DEPRECATION")
    when (intent?.action) {
      Intent.ACTION_SEND ->
        (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { uris.add(it) }
      Intent.ACTION_SEND_MULTIPLE ->
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
      Intent.ACTION_VIEW ->
        intent.data?.let { uris.add(it) }
    }
    if (uris.isNotEmpty()) {
      Toast.makeText(this, importManagedFiles(this, uris), Toast.LENGTH_LONG).show()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    TextToSpeechHelper.shutdown()
  }
}
