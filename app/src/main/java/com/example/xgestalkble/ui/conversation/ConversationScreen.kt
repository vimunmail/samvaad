package com.example.xgestalkble.ui.conversation

import androidx.compose.foundation.layout.imePadding

import android.app.Activity
import androidx.compose.ui.res.painterResource
import com.example.xgestalkble.R
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignLanguage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material3.AlertDialog
import com.example.xgestalkble.ble.BleConnectionState
import com.example.xgestalkble.ml.GesturePredictionEngine
import com.example.xgestalkble.ml.GestureModelFiles
import com.example.xgestalkble.ml.SegmentedGestureRecognizer
import com.example.xgestalkble.ml.SessionCalibrator
import com.example.xgestalkble.ml.RandomForestClassifier
import com.example.xgestalkble.ml.RfcFeatureExtractor
import com.example.xgestalkble.ml.TcnClassifier
import com.example.xgestalkble.ml.LstmClassifier
import com.example.xgestalkble.theme.AmberCalibrate
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.MidnightBg
import com.example.xgestalkble.theme.NeonBlue
import com.example.xgestalkble.theme.ObsidianBg
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.SubtleBg
import com.example.xgestalkble.theme.VioletAccent
import com.example.xgestalkble.theme.ClayShapeLarge
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.ClayShadow
import com.example.xgestalkble.theme.ClayHighlight
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayShadow
import com.example.xgestalkble.ui.components.LanguageSettingsDialog
import com.example.xgestalkble.ui.components.SensorHealthIndicator
import com.example.xgestalkble.ui.main.MainScreenViewModel
import com.example.xgestalkble.utils.ChatLanguage
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.LanguageConfig
import com.example.xgestalkble.utils.PermissionUtils
import com.example.xgestalkble.utils.RecordingModelType
import com.example.xgestalkble.utils.SignImages
import com.example.xgestalkble.utils.SpeechToTextHelper
import com.example.xgestalkble.utils.TextToSpeechHelper
import com.example.xgestalkble.utils.whisper.WhisperModelManager
import com.example.xgestalkble.utils.whisper.WhisperSpeechRecognizer
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single chat bubble. [fromGlove] = gesture from the wearer (outgoing, right).
 * [translation] holds the wearer's selected-language meaning; [translations]
 * carries prepared regional meanings for approved QR guests.
 */
data class ChatMessage(
    val text: String,
    val fromGlove: Boolean,
    val translation: String? = null,
    val imageAsset: String? = null,   // assets/images/** path for a sign picture
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = java.util.UUID.randomUUID().toString(),
    val source: String = if (fromGlove) "sign" else "speech",
    val senderName: String? = null,
    val translations: Map<String, String> = emptyMap()
)

private enum class ConversationStage { SETUP, CHAT, HISTORY }

/**
 * Which `assets/images/<folder>` a model's signs draw their pictures from.
 * Known models are mapped explicitly (their names don't always match a folder);
 * otherwise the model's own name is used as the folder.
 */
private fun modelImageFolder(modelFile: String): String {
    val n = modelFile.lowercase(Locale.US)
    return when {
        "emergency" in n -> "emergency"
        "digit" in n -> "numbers"
        "ges1" in n || "public" in n -> "public-signs"
        "color" in n -> "color-signs"
        "alphabet" in n -> "alphabets"
        "emotion" in n -> "emotional"
        "education" in n -> "educational-signs"
        "hospital" in n -> "Hospital"
        else -> modelFile.removeSuffix(".json")
            .removeSuffix("_rfc").removeSuffix("_tcn").removeSuffix("_lstm").trim()
    }
}

/**
 * True for any idle/rest label — "REST", "REST1", "REST2", … — which must never
 * appear in the chat or be spoken. Labels reach here already uppercased.
 */
private fun isRestLabel(label: String): Boolean =
    label.trim().uppercase(Locale.US).startsWith("REST")

@Composable
fun ConversationScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stage by remember { mutableStateOf(ConversationStage.SETUP) }
    var modelType by remember { mutableStateOf(RecordingModelType.RFC) }
    var modelFile by remember { mutableStateOf("") }

    when (stage) {
        ConversationStage.SETUP -> ConversationSetupScreen(
            modelType = modelType,
            onModelTypeChange = { modelType = it },
            selectedModel = modelFile,
            onModelSelected = { modelFile = it },
            onStart = { stage = ConversationStage.CHAT },
            onHistory = { stage = ConversationStage.HISTORY },
            onBackClick = onBackClick,
            modifier = modifier
        )
        ConversationStage.CHAT -> ConversationChatScreen(
            modelType = modelType,
            modelFile = modelFile,
            onBackClick = { stage = ConversationStage.SETUP },
            onOpenHistory = { stage = ConversationStage.HISTORY },
            modifier = modifier
        )
        ConversationStage.HISTORY -> ConversationHistoryScreen(
            onBack = { stage = ConversationStage.SETUP },
            modifier = modifier
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SETUP: pick the trained model to converse with
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ConversationSetupScreen(
    modelType: RecordingModelType,
    onModelTypeChange: (RecordingModelType) -> Unit,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDropdown by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    val outputLanguage = LanguageConfig.voiceLanguage
    val preparationKey = selectedModel to modelType
    var readyKey by remember { mutableStateOf<Pair<String, RecordingModelType>?>(null) }
    var preparationError by remember { mutableStateOf<String?>(null) }
    var preparedCount by remember { mutableStateOf(0) }
    var labelCount by remember { mutableStateOf(0) }
    var retryPreparation by remember { mutableStateOf(0) }

    LaunchedEffect(preparationKey, retryPreparation) {
        readyKey = null
        preparationError = null
        preparedCount = 0
        labelCount = 0
        if (selectedModel.isEmpty()) return@LaunchedEffect
        try {
            GestureTranslator.ensureLoaded(context)
            val labels = withContext(Dispatchers.IO) {
                val json = JSONObject(File(context.getExternalFilesDir("models"), selectedModel).readText())
                val values = json.optJSONArray("classes") ?: json.optJSONArray("labels")
                    ?: error("This model has no gesture labels. Select another model.")
                (0 until values.length()).map { values.getString(it) }
            }
            if (GestureTranslator.prepareForConversation(labels, outputLanguage) { ready, total ->
                    preparedCount = ready; labelCount = total
                }) readyKey = preparationKey
            else preparationError = GestureTranslator.error.value ?: "Could not prepare all gesture meanings. Please retry."
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { preparationError = error.message ?: "Could not read the model. Select another model." }
    }

    // Use the exact same metadata-based picker as Live Prediction. Imported
    // models are often renamed, so filename suffixes (_lstm.json, etc.) are not
    // reliable. The newest compatible model is selected by default in both flows.
    LaunchedEffect(modelType) {
        val files = withContext(Dispatchers.IO) {
            GestureModelFiles.findForType(context.getExternalFilesDir("models"), modelType)
        }
        availableModels = files
        if (selectedModel !in files) {
            onModelSelected(files.firstOrNull().orEmpty())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ObsidianBg, MidnightBg)))
            .padding(20.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to dashboard",
                    tint = DarkSlateText
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "START CONVERSATION",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = DarkSlateText,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Pick a sign model — or go speech-only",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText
                )
            }
            // Saved transcripts (history + search + export/share)
            IconButton(onClick = onHistory) {
                Icon(Icons.Default.History, contentDescription = "Saved conversations", tint = ElectricCyan)
            }
        }

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Intro card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clayCard(ClayShapeLarge)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clayShadow(ClayShapeSmall, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                            .clip(ClayShapeSmall)
                            .background(Brush.linearGradient(listOf(VioletAccent, ElectricCyan)))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Two-way chat",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = DarkSlateText
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Your signs become spoken text on the right. The other person's speech is transcribed on the left.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Text("Sign text and voice language", color = DarkSlateText, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = "${outputLanguage.displayName} · ${outputLanguage.nativeName}",
                    onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { IconButton(onClick = { showLanguageMenu = true }) {
                        Icon(Icons.Default.ArrowDropDown, "Choose conversation output language")
                    } })
                DropdownMenu(expanded = showLanguageMenu, onDismissRequest = { showLanguageMenu = false }) {
                    ChatLanguage.entries.forEach { language ->
                        DropdownMenuItem(text = { Text("${language.displayName} · ${language.nativeName}") }, onClick = {
                            LanguageConfig.setVoiceLanguage(context, language); showLanguageMenu = false
                        })
                    }
                }
            }

            // Model TYPE toggle
            Text(
                "MODEL TYPE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = SlateText,
                letterSpacing = 2.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelTypeButton(
                    label = "RFC",
                    selected = modelType == RecordingModelType.RFC,
                    modifier = Modifier.weight(1f)
                ) { onModelTypeChange(RecordingModelType.RFC) }
                ModelTypeButton(
                    label = "TCN",
                    selected = modelType == RecordingModelType.TCN,
                    modifier = Modifier.weight(1f)
                ) { onModelTypeChange(RecordingModelType.TCN) }
                ModelTypeButton(
                    label = "LSTM",
                    selected = modelType == RecordingModelType.LSTM,
                    modifier = Modifier.weight(1f)
                ) { onModelTypeChange(RecordingModelType.LSTM) }
            }

            // Model FILE dropdown
            Text(
                "MODEL  (e.g. DIGITS, EMERGENCY, PUBLIC SIGNS)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = SlateText,
                letterSpacing = 1.sp
            )
            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = if (selectedModel.isEmpty()) "No models found" else selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Select model file (.json)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = CardBorder.copy(alpha = 0.4f),
                        focusedLabelColor = ElectricCyan,
                        unfocusedLabelColor = SlateText,
                        focusedTextColor = DarkSlateText,
                        unfocusedTextColor = DarkSlateText
                    ),
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, null, tint = ElectricCyan)
                    }
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable { showDropdown = !showDropdown }
                )
                DropdownMenu(
                    expanded = showDropdown,
                    onDismissRequest = { showDropdown = false },
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .background(MidnightBg)
                        .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(8.dp))
                ) {
                    if (availableModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No ${modelType.name} models found — train one or pick another type", color = SlateText) },
                            onClick = { showDropdown = false }
                        )
                    } else {
                        availableModels.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name, color = DarkSlateText) },
                                onClick = {
                                    onModelSelected(name)
                                    showDropdown = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (selectedModel.isNotEmpty()) {
            when {
                preparationError != null -> {
                    Text(preparationError!!, color = CrimsonRed)
                    TextButton(onClick = { retryPreparation++ }) { Text("Retry preparing translations") }
                }
                readyKey != preparationKey -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Preparing all 12 languages: $preparedCount of $labelCount gesture translations. This first preparation can take a while; saved meanings are reused next time.",
                        color = SlateText, modifier = Modifier.padding(vertical = 8.dp))
                }
                else -> Text("All gesture meanings are ready in all 12 languages. You can switch languages during chat.",
                    color = AuroraGreen, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        // Speech-only chat still opens immediately. Sign chat waits for every meaning.
        Button(
            onClick = { if (selectedModel.isEmpty() || readyKey == preparationKey) onStart() },
            enabled = selectedModel.isEmpty() || readyKey == preparationKey,
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = CardBorder
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (selectedModel.isEmpty()) "OPEN CHAT (SPEECH ONLY)" else "OPEN CHAT",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
            }
        }
    }
}

@Composable
private fun ModelTypeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent,
            contentColor = if (selected) ElectricCyan else SlateText
        ),
        border = BorderStroke(1.dp, if (selected) ElectricCyan else CardBorder.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
        modifier = modifier
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CHAT: live gesture → text+voice  and  speech → text
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConversationChatScreen(
    modelType: RecordingModelType,
    modelFile: String,
    onBackClick: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(context.applicationContext) }
    val connectionState by viewModel.connectionState.collectAsState()
    val dataPacket by viewModel.dataPacket.collectAsState()
    val isConnected = connectionState == BleConnectionState.CONNECTED

    // ── Offline multi-language support ──
    // Two independent directions, persisted in LanguageConfig and editable from
    // the top-bar globe: the wearer's signs are translated + spoken in
    // voiceLanguage (bundled dictionary, no network), while the hearing person's
    // speech is transcribed in sttLanguage.
    val voiceLang = LanguageConfig.voiceLanguage
    val translationRevision by GestureTranslator.revision.collectAsState()
    val sttLang = LanguageConfig.sttLanguage
    var showLangDialog by remember { mutableStateOf(false) }
    var ttsLangWarning by remember { mutableStateOf<String?>(null) }

    // Big "caption" mode — shows the latest sign meaning / spoken phrase large
    // and glanceable, instead of small scrolling bubbles.
    var captionMode by remember { mutableStateOf(false) }

    // Text-based chat by default. Sign pictures are always resolved and stored on
    // each message, but only DISPLAYED when the user turns this on from the top
    // bar ("sign → image"). Toggling it applies to every bubble, old and new.
    var showSignImages by remember { mutableStateOf(true) }

    // SIGN → REST MODE (TCN/LSTM only): nothing is predicted while the sign is
    // in progress; the whole gesture (motion start → return to rest) is
    // classified exactly once at the rest boundary. Toggled from the top bar
    // (hand icon) — best for multi-part ISL signs.
    var signRestMode by remember { mutableStateOf(false) }

    // Echo guard: true while the phone is speaking a gesture aloud. Speech-to-text
    // is muted while this is true (and for a short tail after) so the mic never
    // transcribes the phone's own voice back into the chat.
    var ttsSpeaking by remember { mutableStateOf(false) }
    var lastTtsEndMs by remember { mutableStateOf(0L) }
    // True when incoming audio should be IGNORED as our own TTS echo. The tail is
    // short because the recognizer is fully PAUSED while TTS speaks — a resumed
    // session starts fresh and can't contain our own voice, so a long tail would
    // only throw away the other person's first words.
    fun isOwnEcho(): Boolean =
        ttsSpeaking || (System.currentTimeMillis() - lastTtsEndMs) < 300L

    LaunchedEffect(Unit) {
        GestureTranslator.ensureLoaded(context.applicationContext)
        TextToSpeechHelper.init(context.applicationContext)
    }

    // Apply the chosen voice language to TTS.
    LaunchedEffect(voiceLang) {
        val ok = TextToSpeechHelper.setLanguage(voiceLang.locale)
        ttsLangWarning = if (!ok && voiceLang != ChatLanguage.ENGLISH)
            "${voiceLang.nativeName} voice isn't installed — install its TTS voice in system settings. Showing text only."
        else null
    }

    val qrViewModel: QrConversationViewModel = viewModel()
    val qrState by qrViewModel.state.collectAsState()
    val messages = qrViewModel.localMessages
    var typedReply by remember { mutableStateOf("") }
    fun addLocalMessage(message: ChatMessage) {
        messages.add(message)
        if (message.fromGlove || message.source == "speech") qrViewModel.send(message)
    }
    LaunchedEffect(qrState.incoming) {
        val existingIds = messages.map { it.id }.toHashSet()
        qrState.incoming.filter { it.id !in existingIds }.forEach { messages.add(it) }
    }
    val chatLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(chatLifecycle, qrViewModel) {
        qrViewModel.setForeground(chatLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ ->
            qrViewModel.setForeground(chatLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        chatLifecycle.addObserver(observer)
        onDispose { chatLifecycle.removeObserver(observer); qrViewModel.setForeground(false) }
    }
    val listState = rememberLazyListState()

    // ── Model loading (mirrors PredictionScreen) ──
    var loadedRfc by remember { mutableStateOf<RandomForestClassifier?>(null) }
    var loadedTcn by remember { mutableStateOf<TcnClassifier?>(null) }
    var loadedLstm by remember { mutableStateOf<LstmClassifier?>(null) }
    var modelLabels by remember { mutableStateOf<List<String>>(listOf("REST")) }
    var preparedChatKey by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val chatPreparationKey = modelFile to modelLabels
    var chatPreparationError by remember { mutableStateOf<String?>(null) }
    var chatPreparationRetry by remember { mutableStateOf(0) }
    var chatPreparedCount by remember { mutableStateOf(0) }
    var chatLabelCount by remember { mutableStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // Per-session drift compensation (survives a glove power-cycle). Declared
    // before the model-load effect that calls setReference on it.
    val calibrator = remember { SessionCalibrator() }
    // Type actually loaded — auto-detected from the file, NOT the setup toggle, so
    // a mismatched RFC/TCN selection can't silently stop all prediction.
    var effectiveType by remember { mutableStateOf(modelType) }

    LaunchedEffect(modelFile, modelType) {
        loadedRfc = null
        loadedTcn = null
        loadedLstm = null
        loadError = null
        calibrator.setReference(null)
        if (modelFile.endsWith(".json") || modelFile.endsWith(".lstm")) {
            try {
                val file = File(context.getExternalFilesDir("models"), modelFile)
                val json = JSONObject(file.readText())
                // Detect the real model kind from the file and load that, regardless
                // of the RFC/TCN/LSTM toggle picked on the setup screen.
                val mt = json.optString("model_type").lowercase(Locale.US)
                val isLstmFile = mt == "lstm"
                val isTcnFile = mt == "tcn" || (json.has("weights") && !isLstmFile)
                when {
                    isLstmFile -> {
                        loadedLstm = LstmClassifier.loadFromFile(file)
                        modelLabels = loadedLstm!!.classes.map { it.uppercase() }
                        calibrator.setReference(loadedLstm!!.restBaseline)
                        effectiveType = RecordingModelType.LSTM
                    }
                    isTcnFile -> {
                        loadedTcn = TcnClassifier.loadFromFile(file)
                        modelLabels = loadedTcn!!.classes.map { it.uppercase() }
                        calibrator.setReference(loadedTcn!!.restBaseline)
                        effectiveType = RecordingModelType.TCN
                    }
                    else -> {
                        loadedRfc = RandomForestClassifier.loadFromFile(file)
                        modelLabels = loadedRfc!!.classes.map { it.uppercase() }
                        calibrator.setReference(loadedRfc!!.restBaseline)
                        effectiveType = RecordingModelType.RFC
                    }
                }
            } catch (e: Exception) {
                loadError = "Failed to load model: ${e.message}"
            }
        }
    }

    // All language meanings are cached before accepting signs; voice changes reuse them.
    val hasLoadedModel = loadedRfc != null || loadedTcn != null || loadedLstm != null
    LaunchedEffect(chatPreparationKey, hasLoadedModel, chatPreparationRetry) {
        preparedChatKey = null
        chatPreparationError = null
        if (!hasLoadedModel) return@LaunchedEffect
        try {
            if (GestureTranslator.prepareForConversation(modelLabels, voiceLang) { ready, total ->
                    chatPreparedCount = ready; chatLabelCount = total
                }) preparedChatKey = chatPreparationKey
            else chatPreparationError = GestureTranslator.error.value ?: "Please retry preparing gesture meanings."
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { chatPreparationError = error.message ?: "Please retry preparing gesture meanings." }
    }

    // ── Prediction: shared stabilization + rest→gesture→rest segmentation ──
    // Same engine the Live Prediction screen uses, so behavior is identical. The
    // chat only acts on the per-cycle COMMITTED gesture (one per rest→sign→rest),
    // and never shows rest labels.
    val tcnSequence = remember { ArrayDeque<FloatArray>(30) }
    val rfcWindow = remember { ArrayDeque<FloatArray>(RfcFeatureExtractor.WINDOW) }
    var tcnFrameCounter by remember { mutableStateOf(0) }
    val tcnInferenceInterval = 3
    val engine = remember { GesturePredictionEngine() }
    val segRecognizer = remember { SegmentedGestureRecognizer() }

    // Live recognition read-out so the chat visibly reacts while you sign.
    var liveLabel by remember { mutableStateOf("REST") }
    var liveConf by remember { mutableStateOf(0f) }
    var atRest by remember { mutableStateOf(true) }

    // Stable recognition status tracking.
    var isConfident by remember { mutableStateOf(false) }
    var lastTriggeredGesture by remember { mutableStateOf("") }
    var lastTriggerTime by remember { mutableStateOf(0L) }

    // Clear live state when the glove drops, so nothing stale fires on reconnect.
    LaunchedEffect(isConnected) {
        if (!isConnected) {
            tcnSequence.clear(); rfcWindow.clear(); engine.reset(); segRecognizer.reset(); calibrator.resetSession(); tcnFrameCounter = 0
            liveLabel = "REST"; liveConf = 0f; atRest = true; isConfident = false
            lastTriggeredGesture = ""; lastTriggerTime = 0L
        }
    }

    // Switching the sign→rest mode mid-session must not carry stale segment or
    // rolling-window state across into the other pipeline (copied from PredictionScreen).
    LaunchedEffect(signRestMode, voiceLang) {
        engine.reset()
        segRecognizer.reset()
        tcnSequence.clear()
        rfcWindow.clear()
        tcnFrameCounter = 0
    }

    LaunchedEffect(dataPacket, isConnected, preparedChatKey, voiceLang) {
        if (!isConnected || dataPacket == null || preparedChatKey != chatPreparationKey) return@LaunchedEffect
        val features = calibrator.process(dataPacket!!.normalizedFeatures)
        val now = System.currentTimeMillis()

        // Print a bilingual bubble (English label + the chosen language's meaning)
        // and speak it in the chosen voice language. Rest labels are never printed.
        val emit: (String) -> Unit = { g ->
            if (!isRestLabel(g)) {
                val english = g.uppercase(Locale.US)
                val translated = GestureTranslator.translate(g, LanguageConfig.voiceLanguage)
                // Sign → image: show the matching picture as a chat bubble when one
                // is bundled in assets, preferring the model's image folder (e.g.
                // Emergency → emergency/, GES1.0 → public-signs/).
                val image = SignImages.resolve(
                    context, english, preferredFolder = modelImageFolder(modelFile)
                )
                val candidate = ChatMessage(
                        text = GestureTranslator.englishMeaning(g),
                        fromGlove = true,
                        translation = if (!translated.equals(english, ignoreCase = true)) translated else null,
                        imageAsset = image,
                        translations = ChatTranslations.forGuests(context, g)
                    )
                if (qrState.confirmSigns) qrViewModel.reviewGesture(candidate)
                else {
                    addLocalMessage(candidate)
                    GestureTranslator.speak(g, LanguageConfig.voiceLanguage)
                }
            }
        }

        // ── Shared tuning knobs (EXACTLY matching PredictionScreen) ──
        val confFloor = 0.45f
        val marginGate = 0.20f
        val restHoldMs = 350L
        val minGestureMs = 250L
        val commitHoldMs = if (effectiveType == RecordingModelType.RFC) 450L else 0L
        val idleRestMs = 1000L

        // ── SIGN → REST MODE (TCN/LSTM) ──
        // Nothing is predicted while the hand is moving; the complete sign is
        // classified once, at the moment the hand returns to rest.
        if (signRestMode && effectiveType != RecordingModelType.RFC) {
            val segClasses: Array<String>
            val classify: (List<FloatArray>) -> FloatArray?
            when (effectiveType) {
                RecordingModelType.TCN -> {
                    val tcn = loadedTcn ?: return@LaunchedEffect
                    segClasses = tcn.classes
                    classify = { seq -> tcn.predictProbabilities(seq) }
                }
                RecordingModelType.LSTM -> {
                    val lstm = loadedLstm ?: return@LaunchedEffect
                    segClasses = lstm.classes
                    classify = { seq -> lstm.predictProbabilities(seq) }
                }
                else -> return@LaunchedEffect
            }
            // Shared tuning knobs matching PredictionScreen
            segRecognizer.confFloor = confFloor
            segRecognizer.margin = marginGate
            segRecognizer.restHoldMs = restHoldMs
            segRecognizer.minGestureMs = minGestureMs

            val segTick = segRecognizer.update(now, features, classify, segClasses)
            val signing = segTick.phase == SegmentedGestureRecognizer.Phase.SIGNING
            atRest = !signing
            isConfident = signing
            liveLabel = if (signing) "SIGNING…" else "REST"
            liveConf = if (signing) 0f else segRecognizer.dbgTop1
            segTick.committed?.let { g ->
                if (g != lastTriggeredGesture || now - lastTriggerTime >= 1500L) {
                    lastTriggeredGesture = g
                    lastTriggerTime = now
                    emit(g)
                }
            }
            return@LaunchedEffect
        }

        // Push live-tunable knobs into the engine (EXACTLY matching PredictionScreen).
        engine.confFloor = confFloor
        engine.margin = marginGate
        engine.restHoldMs = restHoldMs
        engine.minGestureMs = minGestureMs
        engine.commitHoldMs = commitHoldMs
        engine.idleRestMs = idleRestMs
        engine.staticMode = (effectiveType == RecordingModelType.RFC)

        // Get a fresh probability vector when one is available, else null (the
        // engine still advances its stillness clock so rest is detected promptly).
        var probs: FloatArray? = null
        var classes: Array<String> = emptyArray()
        when (effectiveType) {
            RecordingModelType.RFC -> {
                val rfc = loadedRfc ?: return@LaunchedEffect
                classes = rfc.classes
                if (rfc.windowed) {
                    rfcWindow.addLast(features.copyOf())
                    while (rfcWindow.size > rfc.window) rfcWindow.removeFirst()
                    if (rfcWindow.size >= rfc.window) {
                        probs = rfc.predictProbabilities(RfcFeatureExtractor.extract(rfcWindow.toList()))
                    }
                } else {
                    probs = rfc.predictProbabilities(features)
                }
            }
            RecordingModelType.TCN -> {
                val tcn = loadedTcn ?: return@LaunchedEffect
                classes = tcn.classes
                tcnSequence.addLast(features.copyOf())
                if (tcnSequence.size > 30) tcnSequence.removeFirst()
                if (tcnSequence.size == 30) {
                    tcnFrameCounter++
                    if (tcnFrameCounter >= tcnInferenceInterval) {
                        tcnFrameCounter = 0
                        probs = tcn.predictProbabilities(tcnSequence.toList())
                    }
                }
            }
            RecordingModelType.LSTM -> {
                val lstm = loadedLstm ?: return@LaunchedEffect
                classes = lstm.classes
                // Same 30-frame rolling window + throttle as the TCN.
                tcnSequence.addLast(features.copyOf())
                if (tcnSequence.size > 30) tcnSequence.removeFirst()
                if (tcnSequence.size == 30) {
                    tcnFrameCounter++
                    if (tcnFrameCounter >= tcnInferenceInterval) {
                        tcnFrameCounter = 0
                        probs = lstm.predictProbabilities(tcnSequence.toList())
                    }
                }
            }
        }

        // Run the shared stabilization + rest→gesture→rest segmentation.
        val tick = engine.update(now, features, probs, classes)
        liveLabel = tick.liveLabel
        liveConf = tick.liveConf
        atRest = tick.atRest
        isConfident = tick.isConfident

        // One gesture committed per rest→gesture→rest cycle — emit to chat.
        tick.committed?.let { g ->
            if (g != lastTriggeredGesture || now - lastTriggerTime >= 1500L) {
                lastTriggeredGesture = g
                lastTriggerTime = now
                emit(g)
            }
            // Clear rolling inference buffers on commit to prevent ghost triggers
            // from the old gesture's sequence history.
            tcnSequence.clear()
            rfcWindow.clear()
        }
    }

    // ── Speech-to-text (the hearing person) ──
    var micListening by remember { mutableStateOf(false) }
    var partialText by remember { mutableStateOf("") }
    LaunchedEffect(partialText) { qrViewModel.updateCaption(partialText) }
    var sttError by remember { mutableStateOf<String?>(null) }
    var sttInfo by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val modelManager = remember { WhisperModelManager(context.applicationContext) }
    var whisperReady by remember { mutableStateOf(modelManager.isReady()) }
    var downloadingModel by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadLabel by remember { mutableStateOf("") }
    var showDownloadDialog by remember { mutableStateOf(false) }

    val offlineSpeech = remember(whisperReady) {
        if (whisperReady) {
            WhisperSpeechRecognizer(context.applicationContext, modelManager).apply {
                onPartial = { partialText = if (isOwnEcho()) "" else it }
                onFinal = { text ->
                    partialText = ""
                    if (!isOwnEcho()) {
                        sttError = null
                        sttInfo = null
                        addLocalMessage(ChatMessage(text = text, fromGlove = false))
                    }
                }
                onListeningChanged = { micListening = it; if (!it) partialText = "" }
                onError = { sttError = it; partialText = "" }
                onInfo = { sttInfo = it }
            }
        } else null
    }

    val speech = remember {
        SpeechToTextHelper(context.applicationContext).apply {
            onPartial = { partialText = if (isOwnEcho()) "" else it }
            onFinal = { text ->
                partialText = ""
                if (!isOwnEcho()) {
                    sttError = null
                    sttInfo = null
                    addLocalMessage(ChatMessage(text = text, fromGlove = false))
                }
            }
            onListeningChanged = { micListening = it; if (!it) partialText = "" }
            onError = { sttError = it; partialText = "" }
            onInfo = { sttInfo = it }
        }
    }

    // While the phone speaks a gesture aloud, the speech recognizer is fully
    // PAUSED (not just text-muted): an active recognition session makes many
    // phones duck or outright silence media playback, which is why the spoken
    // voice sometimes "didn't come" while the mic was on. It also guarantees the
    // mic can never transcribe our own voice. The mic resumes by itself shortly
    // after the utterance ends.
    var micPausedForTts by remember { mutableStateOf(false) }
    LaunchedEffect(speech) {
        TextToSpeechHelper.onSpeakingChanged = { speaking ->
            ttsSpeaking = speaking
            if (speaking) {
                if (micListening) {
                    micPausedForTts = true
                    partialText = ""
                    speech.stop()
                }
            } else {
                lastTtsEndMs = System.currentTimeMillis()
                if (micPausedForTts) {
                    // Keep the flag set until the mic ACTUALLY resumes: if another
                    // sign is spoken during the delay, this resume is skipped but
                    // the next utterance-end schedules a fresh one — the mic can
                    // never be left permanently off.
                    coroutineScope.launch {
                        kotlinx.coroutines.delay(400)
                        if (micPausedForTts && !ttsSpeaking && !micListening && chatLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            micPausedForTts = false
                            speech.start()
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(offlineSpeech, speech) {
        onDispose {
            offlineSpeech?.destroy()
            speech.destroy()
            TextToSpeechHelper.onSpeakingChanged = null
        }
    }
    DisposableEffect(chatLifecycle, speech, offlineSpeech) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                micPausedForTts = false; offlineSpeech?.stop(); speech.stop(); partialText = ""
            }
        }
        chatLifecycle.addObserver(observer)
        onDispose { chatLifecycle.removeObserver(observer) }
    }

    // Always use the fast streaming online recognizer. The Whisper offline model
    // pads every phrase to a fixed 30 s chunk and runs the full model on-device,
    // which takes several seconds per utterance — far too slow for live chat.
    val useOffline = false

    // Transcribe the hearing person in the selected language; if the mic is
    // already live, restart so the change takes effect immediately.
    LaunchedEffect(sttLang) {
        speech.languageTag = sttLang.bcp47
        offlineSpeech?.languageTag = sttLang.bcp47
        if (micListening) {
            if (useOffline) {
                offlineSpeech?.stop()
                offlineSpeech?.start()
            } else {
                speech.stop()
                speech.start()
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            sttError = null
            sttInfo = null
            if (useOffline) offlineSpeech?.start() else speech.start()
        } else {
            sttError = "Microphone permission denied."
        }
    }

    val toggleMic = {
        if (micListening) {
            if (useOffline) offlineSpeech?.stop() else speech.stop()
            partialText = ""
        } else if (PermissionUtils.hasAudioPermission(context)) {
            sttError = null
            sttInfo = null
            // Fast streaming online recognition for every language.
            speech.start()
        } else {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showDownloadDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = {
                Text(
                    text = "Download Offline Speech Model?",
                    fontWeight = FontWeight.Bold,
                    color = DarkSlateText
                )
            },
            text = {
                Text(
                    text = "To enable 100% offline speech recognition for English, the app needs to download the speech model (~142 MB). Once downloaded, English transcribing runs fully on-device with zero internet required. Indian languages use the system's online recognizer.\n\nAlternatively, you can use system online speech recognition.",
                    color = SlateText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDownloadDialog = false
                        downloadingModel = true
                        sttInfo = "Starting download..."
                        coroutineScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                modelManager.ensureModel { fraction, label ->
                                    downloadProgress = fraction
                                    downloadLabel = label
                                }
                            }
                            withContext(Dispatchers.Main) {
                                downloadingModel = false
                                if (ok) {
                                    whisperReady = true
                                    sttInfo = "Offline speech model ready! Tap mic to start speaking."
                                } else {
                                    sttError = "Model download failed. Using online speech fallback."
                                    speech.start()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    Text("DOWNLOAD (142 MB)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDownloadDialog = false
                        sttInfo = "Using online speech recognition..."
                        speech.start()
                    }
                ) {
                    Text("USE ONLINE FALLBACK", color = ElectricCyan)
                }
            },
            containerColor = CardBg
        )
    }

    if (downloadingModel) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = "Downloading Offline Model",
                    fontWeight = FontWeight.Bold,
                    color = DarkSlateText
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = downloadLabel,
                        color = SlateText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = ElectricCyan,
                        trackColor = CardBorder.copy(alpha = 0.2f)
                    )
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            confirmButton = {},
            containerColor = CardBg
        )
    }

    // Auto-scroll to the newest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Used only for history/export organization — the chat UI itself never shows
    // the model's name (deliberate: the person across the table shouldn't see it).
    val modelTitle = modelFile.removeSuffix(".json").ifEmpty { "Conversation" }

    // ── History / export / share of the running conversation ──
    var showChatMenu by remember { mutableStateOf(false) }
    var analyzeConv by remember { mutableStateOf<SavedConversation?>(null) }
    // "Gesture audio": system-voice recordings of each sign, playable + shareable.
    var showAudioDialog by remember { mutableStateOf(false) }
    // How many messages were in the last persisted copy, so back-out auto-save and
    // the manual "Save" don't write duplicate transcripts.
    var lastSavedCount by remember { mutableStateOf(0) }

    fun snapshot(): SavedConversation {
        val now = System.currentTimeMillis()
        return SavedConversation(
            id = "live_$now",
            title = modelTitle,
            startedAt = messages.firstOrNull()?.timestamp ?: now,
            endedAt = messages.lastOrNull()?.timestamp ?: now,
            messages = messages.toList()
        )
    }

    fun saveCurrent(notify: Boolean) {
        if (messages.isEmpty()) {
            if (notify) Toast.makeText(context, "Nothing to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        ConversationStore.save(context, messages.toList(), modelTitle)
        lastSavedCount = messages.size
        if (notify) Toast.makeText(context, "Saved to history", Toast.LENGTH_SHORT).show()
    }

    // Persist the transcript automatically when leaving the chat (unless already saved).
    val handleBack = {
        if (messages.isNotEmpty() && messages.size != lastSavedCount) saveCurrent(false)
        onBackClick()
    }

    // Rotation keeps the QR session and transcript in the ViewModel. Leaving
    // this conversation closes guest access and saves the complete transcript.
    DisposableEffect(qrViewModel) {
        onDispose {
            if ((context as? Activity)?.isChangingConfigurations != true) {
                if (messages.isNotEmpty() && messages.size != lastSavedCount) saveCurrent(false)
                qrViewModel.endSession()
                messages.clear()
            }
        }
    }

    analyzeConv?.let { AnalyzeDialog(it) { analyzeConv = null } }

    if (showAudioDialog) {
        GestureAudioDialog(
            messages = messages.toList(),
            title = modelTitle,
            onDismiss = { showAudioDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .imePadding()
    ) {
        // ── WhatsApp-style top bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBg)
                .padding(start = 4.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = handleBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DarkSlateText,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clayShadow(CircleShape, ClayShadow, ClayHighlight, offset = 4.dp, blur = 8.dp)
                    .clip(CircleShape)
                    .background(CardBg)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.samvaad_logo),
                    contentDescription = "Samvaad Logo",
                    modifier = Modifier.fillMaxSize().padding(5.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "Conversation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = DarkSlateText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Connection status: one aligned line — colored dot, state text,
                // then the 6 sensor-health micro-dots (Thumb…Wrist).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val packet by viewModel.dataPacket.collectAsState()
                    val ready = packet?.isReady ?: false
                    val statusColor = when {
                        !isConnected -> CrimsonRed
                        ready -> AuroraGreen
                        else -> AmberCalibrate
                    }
                    Box(
                        Modifier.size(7.dp).clip(CircleShape).background(statusColor)
                    )
                    Text(
                        text = when {
                            !isConnected -> "Glove offline · mic still works"
                            ready -> "Online · Ready"
                            else -> "Online · Settling"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isConnected) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val status = packet?.sensorStatus ?: 0
                            // Thumb, Index, Middle, Ring, Little, Wrist
                            val sensorBits = listOf(3, 1, 0, 4, 2, 5)
                            sensorBits.forEach { bit ->
                                val healthy = packet != null && (status and (1 shl bit)) != 0
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(if (healthy) AuroraGreen else CrimsonRed)
                                )
                            }
                        }
                    }
                }
            }

            // Action cluster: only the two most-used toggles stay in the bar
            // (compact 40dp buttons so the title always keeps its space);
            // everything else lives in the overflow menu.

            // Sign → Rest mode toggle (TCN/LSTM only): hold all prediction while
            // signing; classify the complete sign once when the hand rests.
            if (effectiveType != RecordingModelType.RFC) {
                IconButton(
                    onClick = { signRestMode = !signRestMode; segRecognizer.reset() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PanTool,
                        contentDescription = if (signRestMode)
                            "Sign → Rest mode ON (predicts when the hand rests)"
                        else "Sign → Rest mode OFF (live prediction)",
                        tint = if (signRestMode) AuroraGreen else ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            IconButton(
                onClick = { captionMode = !captionMode },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (captionMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (captionMode) "Exit big caption mode" else "Big caption mode",
                    tint = if (captionMode) AuroraGreen else ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Overflow: display toggles + language + save / share / export /
            // analyze / history of this chat.
            Box {
                IconButton(
                    onClick = { showChatMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = DarkSlateText,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = showChatMenu,
                    onDismissRequest = { showChatMenu = false },
                    modifier = Modifier.background(MidnightBg)
                ) {
                    // Sign → image: text-only chat by default; reveal the matching
                    // sign picture on each gesture bubble (and in caption mode).
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showSignImages) "Hide sign images" else "Show sign images",
                                color = DarkSlateText
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (showSignImages) Icons.Default.HideImage else Icons.Default.Image,
                                null,
                                tint = if (showSignImages) AuroraGreen else ElectricCyan
                            )
                        },
                        onClick = { showChatMenu = false; showSignImages = !showSignImages }
                    )
                    DropdownMenuItem(
                        text = { Text("Conversation language", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.Language, null, tint = ElectricCyan) },
                        onClick = { showChatMenu = false; showLangDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Save to history", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.Save, null, tint = ElectricCyan) },
                        onClick = { showChatMenu = false; saveCurrent(true) }
                    )
                    DropdownMenuItem(
                        text = { Text("Share (WhatsApp / text)", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.Share, null, tint = AuroraGreen) },
                        onClick = {
                            showChatMenu = false
                            if (messages.isEmpty()) {
                                Toast.makeText(context, "Nothing to share yet", Toast.LENGTH_SHORT).show()
                            } else {
                                saveCurrent(false)
                                ConversationExporter.shareText(context, snapshot())
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Export as PDF", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, tint = ElectricCyan) },
                        onClick = {
                            showChatMenu = false
                            if (messages.isEmpty()) {
                                Toast.makeText(context, "Nothing to export yet", Toast.LENGTH_SHORT).show()
                            } else {
                                saveCurrent(false)
                                ConversationExporter.sharePdf(context, snapshot())
                            }
                        }
                    )
                    // System-voice audio of every gesture in this chat: hear each
                    // sign and share the clips (WhatsApp voice-note style).
                    DropdownMenuItem(
                        text = { Text("Gesture audio (hear / share)", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.GraphicEq, null, tint = AuroraGreen) },
                        onClick = {
                            showChatMenu = false
                            if (messages.none { it.fromGlove }) {
                                Toast.makeText(context, "No signed gestures yet", Toast.LENGTH_SHORT).show()
                            } else showAudioDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Analyze chat", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.Analytics, null, tint = VioletAccent) },
                        onClick = {
                            showChatMenu = false
                            if (messages.isEmpty()) {
                                Toast.makeText(context, "Nothing to analyze yet", Toast.LENGTH_SHORT).show()
                            } else analyzeConv = snapshot()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Saved conversations", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.History, null, tint = ElectricCyan) },
                        onClick = {
                            showChatMenu = false
                            if (messages.isNotEmpty() && messages.size != lastSavedCount) saveCurrent(false)
                            onOpenHistory()
                        }
                    )
                }
            }
        }

        if (showLangDialog) {
            LanguageSettingsDialog(onDismiss = { showLangDialog = false })
        }

        // ── Message area: big captions or the chat list ──
        if (hasLoadedModel && preparedChatKey != chatPreparationKey) {
            if (chatPreparationError == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Preparing all 12 languages: $chatPreparedCount of $chatLabelCount gesture translations. Sign recognition will resume when ready.",
                    color = SlateText, modifier = Modifier.padding(8.dp))
            } else {
                Text(chatPreparationError!!, color = CrimsonRed, modifier = Modifier.padding(8.dp))
                TextButton(onClick = { chatPreparationRetry++ }) { Text("Retry preparing translations") }
            }
        }
        QrConversationPanel(qrViewModel, qrState)
        qrState.pendingGesture?.let { candidate ->
            var reviewedText by remember(candidate.id) { mutableStateOf(candidate.text) }
            AlertDialog(onDismissRequest = qrViewModel::clearGesture, title = { Text("Review recognized sign") },
                text = { Column {
                    Text("Recognized: ${candidate.text}. Check or correct the meaning before sending.")
                    OutlinedTextField(value = reviewedText, onValueChange = { reviewedText = it.take(2000) },
                        label = { Text("Message to send") }, maxLines = 4)
                } },
                dismissButton = { TextButton(onClick = qrViewModel::clearGesture) { Text("Discard") } },
                confirmButton = { TextButton(enabled = reviewedText.isNotBlank(), onClick = {
                    val corrected = reviewedText.trim()
                    val translation = ChatTranslations.translate(context, corrected, LanguageConfig.voiceLanguage)
                    addLocalMessage(candidate.copy(text = corrected, translation = translation,
                        imageAsset = candidate.imageAsset.takeIf { corrected == candidate.text },
                        translations = if (corrected == candidate.text) candidate.translations
                            else ChatTranslations.forGuests(context, corrected),
                        source = "sign"))
                    qrViewModel.clearGesture()
                    GestureTranslator.speak(corrected, LanguageConfig.voiceLanguage)
                }) { Text("Send sign") } })
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (captionMode) {
                CaptionView(
                    latest = remember(messages.lastOrNull(), voiceLang, translationRevision) {
                        messages.lastOrNull()?.let { msg -> if (msg.fromGlove) msg.copy(translation = ChatTranslations.translate(context, msg.text, voiceLang)) else msg }
                    },
                    livePartial = partialText,
                    listening = micListening,
                    showImage = showSignImages,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.RecordVoiceOver, null,
                        tint = SlateText.copy(alpha = 0.4f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (!isConnected)
                            "Tap the mic below — speech → text works without the glove.\n\nTo add your signs, connect the glove from the home screen."
                        else
                            "Start signing, or tap the mic so the other person can talk.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateText,
                        textAlign = TextAlign.Center
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
            ) {
                itemsIndexed(messages) { _, msg ->
                    ChatBubble(msg, showImage = showSignImages, delivery = qrState.delivery[msg.id],
                        preferredTranslation = remember(msg.text, voiceLang, translationRevision) { ChatTranslations.translate(context, msg.text, voiceLang) })
                }
            }
            }
        }

        // Live recognition feedback while signing — confirms the model is running
        // even before a gesture is committed to a bubble.
        if (isConnected && (loadedRfc != null || loadedTcn != null || loadedLstm != null) && isConfident && !isRestLabel(liveLabel)) {
            StatusStrip(
                if (liveLabel == "SIGNING…")
                    "✋ signing… — predicts when your hand returns to rest"
                else
                    "✋ recognizing: $liveLabel" +
                        (if (liveConf > 0f) "  ${String.format(Locale.US, "%.0f", liveConf * 100)}%" else ""),
                ElectricCyan
            )
        }
        if (micListening && partialText.isNotEmpty()) {
            StatusStrip("🎤 $partialText", ElectricCyan)
        }
        // Speech-to-text problems were previously swallowed silently — the mic
        // looked on but nothing arrived. Show them, and let the error strip open
        // the system voice-input settings (offline pack downloads) in one tap.
        sttError?.let { err ->
            StatusStrip("⚠️ $err", CrimsonRed) { speech.openVoiceInputSettings() }
        }
        sttInfo?.let { info ->
            StatusStrip("ℹ️ $info", AmberCalibrate)
        }

        // One compact composer leaves the remaining height for messages.
        PhoneticReplyComposer(value = typedReply,
            onValueChange = { typedReply = it; qrViewModel.updateTyping(it) },
            onSend = { addLocalMessage(ChatMessage(text = it, fromGlove = true, source = "text")) },
            onQuickReply = { addLocalMessage(ChatMessage(text = it, fromGlove = true, source = "text")) },
            micListening = micListening, onToggleMic = { toggleMic() })
    }
}

/**
 * Big, glanceable single-message view for "caption mode". Shows whatever is most
 * relevant right now, large: the live speech being heard, or the latest sign
 * meaning / spoken phrase. Designed to be read at a distance.
 */
@Composable
private fun CaptionView(
    latest: ChatMessage?,
    livePartial: String,
    listening: Boolean,
    showImage: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            // Live transcription takes priority so the user reads as they speak.
            listening && livePartial.isNotEmpty() -> CaptionBlock(
                tag = "THEY ARE SAYING…",
                tagColor = AuroraGreen,
                big = livePartial,
                bigColor = DarkSlateText,
                sub = null
            )
            latest != null && latest.fromGlove -> CaptionBlock(
                tag = "YOU SIGNED",
                tagColor = ElectricCyan,
                big = latest.translation ?: latest.text,
                bigColor = ElectricCyan,
                sub = if (latest.translation != null) latest.text else null,
                // Only reveal the sign picture when the user turned images on.
                imageAsset = if (showImage) latest.imageAsset else null,
                // Big, loud "speak again" so the hearing person catches it.
                onSpeak = {
                    TextToSpeechHelper.init(context.applicationContext)
                    GestureTranslator.speak(latest.text, LanguageConfig.voiceLanguage)
                }
            )
            latest != null -> CaptionBlock(
                tag = "THEY SAID",
                tagColor = AuroraGreen,
                big = latest.text,
                bigColor = DarkSlateText,
                sub = null
            )
            else -> Text(
                text = "Big caption mode is on.\n\nSign, or tap the mic — the latest message shows here, large and clear.",
                color = SlateText,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CaptionBlock(
    tag: String,
    tagColor: Color,
    big: String,
    bigColor: Color,
    sub: String?,
    imageAsset: String? = null,
    onSpeak: (() -> Unit)? = null
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Big sign picture (e.g. the digit) shown full width with a thin frame.
        imageAsset?.let { path ->
            val bmp = remember(path) { SignImages.loadImageBitmap(context, path, 900) }
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = big,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(2.dp, Color.White, RoundedCornerShape(16.dp))
                )
                Spacer(Modifier.height(20.dp))
            }
        }
        Text(
            text = tag,
            color = tagColor,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = big,
            color = bigColor,
            fontWeight = FontWeight.Black,
            fontSize = 42.sp,
            lineHeight = 50.sp,
            textAlign = TextAlign.Center
        )
        if (sub != null) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = sub,
                color = SlateText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
        // Loud "speak again" button so the other person hears the sign clearly.
        onSpeak?.let { speak ->
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = speak,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp, null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("SPEAK LOUD", fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
internal fun ChatBubble(msg: ChatMessage, showImage: Boolean = true, delivery: String? = null, preferredTranslation: String? = null) {
    val time = remember(msg.timestamp) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(msg.timestamp))
    }
    val context = LocalContext.current

    // Your signs = bold RED bubble. The other person's speech-to-text = BLACK
    // bubble. Both use white text.
    val bubbleColor = if (msg.fromGlove) Color(0xFFE23B3B) else Color(0xFF1A1A1A)
    val textColor = Color.White
    val metaColor = Color.White.copy(alpha = 0.85f)

    // Speech-bubble shape with a little tail at the bottom corner — right for the
    // wearer's signs, left for the other person's speech (WhatsApp style).
    val fromGlove = msg.fromGlove
    val density = LocalDensity.current
    val cornerPx = with(density) { 20.dp.toPx() }
    val tailH = with(density) { 9.dp.toPx() }
    val tailW = with(density) { 13.dp.toPx() }
    val shape = remember(fromGlove, cornerPx, tailH, tailW) {
        GenericShape { size, _ ->
            val w = size.width
            val h = size.height
            val r = cornerPx
            val bb = h - tailH // body bottom (tail hangs below this)
            if (fromGlove) {
                moveTo(r, 0f)
                lineTo(w - r, 0f)
                quadraticBezierTo(w, 0f, w, r)
                lineTo(w, h)             // down to tail tip (bottom-right)
                lineTo(w - tailW, bb)    // back up to the tail base
                lineTo(r, bb)
                quadraticBezierTo(0f, bb, 0f, bb - r)
                lineTo(0f, r)
                quadraticBezierTo(0f, 0f, r, 0f)
                close()
            } else {
                moveTo(r, 0f)
                lineTo(w - r, 0f)
                quadraticBezierTo(w, 0f, w, r)
                lineTo(w, bb - r)
                quadraticBezierTo(w, bb, w - r, bb)
                lineTo(tailW, bb)        // bottom edge to tail base
                lineTo(0f, h)            // down to tail tip (bottom-left)
                lineTo(0f, r)            // up the left edge
                quadraticBezierTo(0f, 0f, r, 0f)
                close()
            }
        }
    }

    // Text-only unless the user turned "sign → image" on.
    val hasImage = showImage && msg.imageAsset != null
    // Image bubbles: thin frame so the picture dominates. Text bubbles: roomier.
    val innerPadding = if (hasImage)
        PaddingValues(start = 7.dp, end = 7.dp, top = 7.dp, bottom = 16.dp)
    else
        PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromGlove) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 180.dp, max = 330.dp)
                .clayShadow(shape, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                .clip(shape)
                .background(bubbleColor)
                // Thin frame on the black speech-to-text bubble so it stands out.
                .then(if (msg.fromGlove) Modifier else Modifier.border(1.5.dp, Color.White.copy(alpha = 0.5f), shape))
                .padding(innerPadding)
        ) {
            // Sign → image: the gesture's picture (digits 0-9, etc.) shown full
            // width with just a thin frame. Only when the user enabled images.
            if (showImage) msg.imageAsset?.let { path ->
                val bmp = remember(path) { SignImages.loadImageBitmap(context, path, 800) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = msg.text,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, Color.White, RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (msg.source == "sign") "👋 ${msg.text}" else msg.text,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = if (msg.fromGlove) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    modifier = Modifier.weight(1f, fill = false)
                )
                IconButton(onClick = {
                    TextToSpeechHelper.init(context.applicationContext)
                    if (msg.fromGlove) GestureTranslator.speak(msg.text, LanguageConfig.voiceLanguage)
                    else TextToSpeechHelper.speak(preferredTranslation ?: msg.translation ?: msg.text)
                }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Speak message",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            // Malayalam meaning under the English label, so mute and Malayalam-
            // speaking people both read the sign.
            (preferredTranslation ?: msg.translation)?.let { ml ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = ml,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = textColor.copy(alpha = 0.92f)
                )
            }
            Spacer(Modifier.height(3.dp))
            SignVideoPreview(msg.text)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = (if (msg.fromGlove) "you · " else if (msg.source == "guest") "${msg.senderName ?: "guest"} · " else "speech · ") + time + (delivery?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = metaColor
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
