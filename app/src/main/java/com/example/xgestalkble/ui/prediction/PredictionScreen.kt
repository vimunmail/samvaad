package com.example.xgestalkble.ui.prediction

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.xgestalkble.ble.BleConnectionState
import com.example.xgestalkble.ml.RandomForestClassifier
import com.example.xgestalkble.ml.RfcFeatureExtractor
import com.example.xgestalkble.ml.TcnClassifier
import com.example.xgestalkble.ml.LstmClassifier
import org.json.JSONObject
import com.example.xgestalkble.theme.AmberCalibrate
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.MidnightBg
import com.example.xgestalkble.theme.ObsidianBg
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.theme.ClayShapeLarge
import com.example.xgestalkble.theme.ClayShapeMedium
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.ClayShadow
import com.example.xgestalkble.theme.ClayHighlight
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayInset
import com.example.xgestalkble.theme.clayShadow
import com.example.xgestalkble.ml.GesturePredictionEngine
import com.example.xgestalkble.ml.GestureModelFiles
import com.example.xgestalkble.ml.SegmentedGestureRecognizer
import com.example.xgestalkble.ml.SessionCalibrator
import com.example.xgestalkble.ui.components.SensorHealthIndicator
import com.example.xgestalkble.ui.main.MainScreenViewModel
import com.example.xgestalkble.utils.GestureLabels
import com.example.xgestalkble.utils.RecordingModelType
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.LanguageConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PredictionScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val translationRevision by GestureTranslator.revision.collectAsState()
    val voiceLanguage = LanguageConfig.voiceLanguage
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(context.applicationContext) }
    val connectionState by viewModel.connectionState.collectAsState()
    val dataPacket by viewModel.dataPacket.collectAsState()
    val packetRate by viewModel.packetRate.collectAsState()
    
    val isConnected = connectionState == BleConnectionState.CONNECTED

    var activeModelType by remember { mutableStateOf(RecordingModelType.RFC) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedModel by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }

    var isPredicting by remember { mutableStateOf(false) }
    var predictedGesture by remember { mutableStateOf("REST") }
    var predictedConfidence by remember { mutableStateOf(0.0f) }
    val predictionLogs = remember { mutableStateListOf<String>() }

    // TTS de-dup
    var lastTriggeredGesture by remember { mutableStateOf("") }
    var lastTriggerTime by remember { mutableStateOf(0L) }

    // Loaded classifiers
    var loadedRfcModel by remember { mutableStateOf<RandomForestClassifier?>(null) }
    var loadedTcnModel by remember { mutableStateOf<TcnClassifier?>(null) }
    var loadedLstmModel by remember { mutableStateOf<LstmClassifier?>(null) }
    var modelLabels by remember { mutableStateOf<List<String>>(listOf("REST")) }

    // Inference buffers
    val tcnSequence = remember { ArrayDeque<FloatArray>(30) }
    val rfcWindow = remember { ArrayDeque<FloatArray>(RfcFeatureExtractor.WINDOW) }
    var tcnFrameCounter by remember { mutableStateOf(0) }
    val tcnInferenceInterval = 3 // run the sequence forward pass every 3rd frame (~17 Hz)

    // Shared stabilization + rest→gesture→rest segmentation engine.
    val engine = remember { GesturePredictionEngine() }
    // SIGN → REST MODE (TCN/LSTM only): no prediction at all while the sign is
    // being performed — the whole gesture is buffered and classified exactly
    // once when the hand returns to rest. Best for multi-part ISL signs whose
    // first half can look like a different sign mid-motion.
    var signRestMode by remember { mutableStateOf(false) }
    val segRecognizer = remember { SegmentedGestureRecognizer() }
    var lastResultConf by remember { mutableStateOf(0f) }
    // Per-session drift compensation — re-anchors the live signal to the model's
    // rest baseline so predictions survive a glove power-cycle.
    val calibrator = remember { SessionCalibrator() }
    // Live (smoothed) read — this screen shows ALL labels in real time.
    var liveLabel by remember { mutableStateOf("REST") }
    var liveConfidence by remember { mutableStateOf(0f) }
    var atRest by remember { mutableStateOf(true) }
    // Stable per-cycle result: the single gesture recognized between two rests,
    // latched on screen for a moment after each cycle.
    var lastResult by remember { mutableStateOf("") }
    var resultUntilMs by remember { mutableStateOf(0L) }
    val resultHoldMs = 2500L

    // Live-tunable knobs (pushed into the engine each frame; tune vs the readout).
    var confFloor by remember { mutableStateOf(0.45f) }    // min top-1 probability
    var marginGate by remember { mutableStateOf(0.20f) }   // min (top1 - top2)
    var restHoldMs by remember { mutableStateOf(350f) }    // still this long ⇒ rest (ms)
    var minGestureMs by remember { mutableStateOf(250f) }  // ignore blips shorter than (ms)
    var commitHoldMs by remember { mutableStateOf(450f) }  // hold a sign this long ⇒ speak it (ms)
    var idleRestMs by remember { mutableStateOf(1000f) }   // no motion this long ⇒ hard rest (ms)

    // Read only models that really match the selected architecture. Imported
    // model names are not reliable, so JSON metadata is the source of truth.
    LaunchedEffect(activeModelType) {
        val files = withContext(Dispatchers.IO) {
            GestureModelFiles.findForType(context.getExternalFilesDir("models"), activeModelType)
        }

        availableModels = files
        // Prefer the newest compatible model, but preserve a valid manual choice.
        selectedModel = selectedModel.takeIf { it in files } ?: files.firstOrNull().orEmpty()
        loadedRfcModel = null
        loadedTcnModel = null
        loadedLstmModel = null

        // Default commitHoldMs to 0f for sequence models (TCN and LSTM) so they only predict
        // on return to rest, and 450f for RFC static poses.
        commitHoldMs = if (activeModelType == RecordingModelType.RFC) 450f else 0f
    }

    // Load selected model when it changes
    LaunchedEffect(selectedModel, activeModelType) {
        loadedRfcModel = null
        loadedTcnModel = null
        loadedLstmModel = null
        calibrator.setReference(null)
        if (selectedModel.isNotEmpty() && (selectedModel.endsWith(".json") || selectedModel.endsWith(".lstm"))) {
            try {
                val file = File(context.getExternalFilesDir("models"), selectedModel)
                val json = JSONObject(file.readText())
                // Detect the real kind from the file. "lstm" is explicit; "tcn" is
                // explicit or implied by a weights blob without an lstm marker; the
                // rest are RFC. Only load when it matches the selected tab.
                val mt = json.optString("model_type").lowercase(Locale.US)
                val isLstmFile = mt == "lstm"
                val isTcnFile = mt == "tcn" || (json.has("weights") && !isLstmFile)

                when (activeModelType) {
                    RecordingModelType.LSTM -> if (isLstmFile) {
                        loadedLstmModel = LstmClassifier.loadFromFile(file)
                        modelLabels = loadedLstmModel!!.classes.map { it.uppercase() }
                        calibrator.setReference(loadedLstmModel!!.restBaseline)
                        android.util.Log.d("Prediction", "Loaded LSTM model: $selectedModel with labels: $modelLabels")
                    }
                    RecordingModelType.TCN -> if (isTcnFile) {
                        loadedTcnModel = TcnClassifier.loadFromFile(file)
                        modelLabels = loadedTcnModel!!.classes.map { it.uppercase() }
                        calibrator.setReference(loadedTcnModel!!.restBaseline)
                        android.util.Log.d("Prediction", "Loaded TCN model: $selectedModel with labels: $modelLabels")
                    }
                    RecordingModelType.RFC -> if (!isTcnFile && !isLstmFile) {
                        loadedRfcModel = RandomForestClassifier.loadFromFile(file)
                        modelLabels = loadedRfcModel!!.classes.map { it.uppercase() }
                        calibrator.setReference(loadedRfcModel!!.restBaseline)
                        android.util.Log.d("Prediction", "Loaded RFC model: $selectedModel with labels: $modelLabels")
                    }
                }
                GestureTranslator.registerModelClasses(modelLabels)
            } catch (e: Exception) {
                android.util.Log.e("Prediction", "Failed to load model: ${e.message}")
            }
        }
    }

    // Switching the sign→rest mode mid-session must not carry stale segment or
    // rolling-window state across into the other pipeline.
    LaunchedEffect(signRestMode) {
        engine.reset()
        segRecognizer.reset()
        tcnSequence.clear()
        rfcWindow.clear()
        tcnFrameCounter = 0
    }

    LaunchedEffect(isPredicting) {
        if (!isPredicting) {
            tcnSequence.clear()
            rfcWindow.clear()
            engine.reset()
            segRecognizer.reset()
            calibrator.resetSession()
            tcnFrameCounter = 0
            lastTriggeredGesture = ""
            lastTriggerTime = 0L
            predictedGesture = "REST"
            predictedConfidence = 0.0f
            liveLabel = "REST"
            liveConfidence = 0f
            atRest = true
            lastResult = ""
            lastResultConf = 0f
            resultUntilMs = 0L
        }
    }

    // State machine prediction processor
    LaunchedEffect(dataPacket, isPredicting) {
        if (!isPredicting || dataPacket == null) return@LaunchedEffect
        // Drift-compensate the raw frame before buffering/inference (no-op for
        // legacy models without a rest_baseline).
        val features = calibrator.process(dataPacket!!.normalizedFeatures)
        val now = System.currentTimeMillis()

        // ── SIGN → REST MODE (TCN/LSTM) ──
        // Nothing is predicted while the hand is moving; the complete sign is
        // classified once, at the moment the hand returns to rest.
        if (signRestMode && activeModelType != RecordingModelType.RFC) {
            val classes: Array<String>
            val classify: (List<FloatArray>) -> FloatArray?
            when (activeModelType) {
                RecordingModelType.TCN -> {
                    val tcn = loadedTcnModel ?: return@LaunchedEffect
                    classes = tcn.classes
                    classify = { seq -> tcn.predictProbabilities(seq) }
                }
                RecordingModelType.LSTM -> {
                    val lstm = loadedLstmModel ?: return@LaunchedEffect
                    classes = lstm.classes
                    classify = { seq -> lstm.predictProbabilities(seq) }
                }
                else -> return@LaunchedEffect
            }
            // The shared tuning sliders that still apply in this mode.
            segRecognizer.confFloor = confFloor
            segRecognizer.margin = marginGate
            segRecognizer.restHoldMs = restHoldMs.toLong()
            segRecognizer.minGestureMs = minGestureMs.toLong()

            val segTick = segRecognizer.update(now, features, classify, classes)
            val signing = segTick.phase == SegmentedGestureRecognizer.Phase.SIGNING
            atRest = !signing
            liveLabel = if (signing) "SIGNING…" else GestureLabels.REST
            liveConfidence = if (signing) 0f else segRecognizer.dbgTop1

            segTick.committed?.let { g ->
                lastResult = g
                lastResultConf = segTick.committedConf
                resultUntilMs = now + resultHoldMs
                if (g != lastTriggeredGesture || now - lastTriggerTime >= 1500L) {
                    GestureTranslator.speak(g, LanguageConfig.voiceLanguage)
                    lastTriggeredGesture = g
                    lastTriggerTime = now
                }
            }

            predictedGesture = when {
                now < resultUntilMs && lastResult.isNotEmpty() -> lastResult
                signing -> "SIGNING…"
                else -> GestureLabels.REST
            }
            predictedConfidence = when {
                now < resultUntilMs && lastResult.isNotEmpty() -> lastResultConf * 100f
                signing -> 0f
                else -> 100f
            }
            return@LaunchedEffect
        }

        // Push live-tunable knobs into the engine.
        engine.confFloor = confFloor
        engine.margin = marginGate
        engine.restHoldMs = restHoldMs.toLong()
        engine.minGestureMs = minGestureMs.toLong()
        engine.commitHoldMs = commitHoldMs.toLong()
        engine.idleRestMs = idleRestMs.toLong()
        // RFC is used for static handshapes — rest comes only from the REST class,
        // not from the hand being still, so a held pose keeps predicting.
        engine.staticMode = activeModelType == RecordingModelType.RFC

        // Get a fresh probability vector when one is available, else null (the
        // engine still advances its stillness clock so rest is detected promptly).
        var probs: FloatArray? = null
        var classes: Array<String> = emptyArray()
        when (activeModelType) {
            RecordingModelType.RFC -> {
                val rfc = loadedRfcModel ?: return@LaunchedEffect
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
                val tcn = loadedTcnModel ?: return@LaunchedEffect
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
                val lstm = loadedLstmModel ?: return@LaunchedEffect
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
        liveConfidence = tick.liveConf
        atRest = tick.atRest

        // One gesture committed per rest→gesture→rest cycle — speak + latch it.
        tick.committed?.let { g ->
            lastResult = g
            resultUntilMs = now + resultHoldMs
            if (g != lastTriggeredGesture || now - lastTriggerTime >= 1500L) {
                GestureTranslator.speak(g, LanguageConfig.voiceLanguage)
                lastTriggeredGesture = g
                lastTriggerTime = now
            }
            // Clear rolling inference buffers on commit to prevent ghost triggers
            // from the old gesture's sequence history.
            tcnSequence.clear()
            rfcWindow.clear()
        }

        // ── Stable main display ──
        //   • the recognized gesture for ~2.5s right after a cycle,
        //   • else REST when idle,
        //   • else the live label if we confidently recognize a gesture,
        //   • else REST when not confident (avoids transition flickering).
        predictedGesture = when {
            now < resultUntilMs && lastResult.isNotEmpty() -> lastResult
            atRest -> GestureLabels.REST
            tick.isConfident && !GestureLabels.isRest(liveLabel) -> liveLabel
            else -> GestureLabels.REST
        }
        predictedConfidence = if (predictedGesture == GestureLabels.REST) 100f else liveConfidence * 100f
    }

    val gestureColorMap = mapOf(
        0 to ElectricCyan,
        1 to AuroraGreen,
        2 to AmberCalibrate,
        3 to CrimsonRed,
        4 to Color(0xFFC084FC)
    )
    val predictedGestureIdx = modelLabels.indexOf(predictedGesture).let { if (it < 0) 0 else it }

    val gestureColor by animateColorAsState(
        targetValue = if (!isPredicting) SlateText else gestureColorMap[predictedGestureIdx % 5] ?: ElectricCyan,
        label = "color"
    )

    val cardBGBnd by animateColorAsState(
        targetValue = if (!isPredicting) CardBg else (gestureColorMap[predictedGestureIdx % 5] ?: ElectricCyan).copy(alpha = 0.04f),
        label = "bg"
    )

    val borderGlow by animateColorAsState(
        targetValue = if (!isPredicting) CardBorder.copy(alpha = 0.2f) else gestureColorMap[predictedGestureIdx % 5] ?: ElectricCyan,
        label = "border"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(ObsidianBg, MidnightBg)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Action Top Bar Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBackClick, enabled = !isPredicting) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (isPredicting) SlateText else DarkSlateText
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "REAL-TIME GESTURE PREDICTION",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = DarkSlateText,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Observe live classifications from the BLE smart glove",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText
                        )
                    }
                }

                // Connection status indicator
                Box(
                    modifier = Modifier
                        .clayCard(ClayShapeSmall)
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) AuroraGreen else CrimsonRed)
                        )
                        Text(
                            text = if (isConnected) "CONNECTED" else "OFFLINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isConnected) AuroraGreen else CrimsonRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // SINGLE SIMPLIFIED GESTURE PREDICTION CARD
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clayCard(ClayShapeLarge, cardBGBnd)
                        .border(BorderStroke(2.dp, borderGlow), ClayShapeLarge)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "REAL-TIME GESTURE PREDICTION",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // 1. Model Type Selection (RFC vs TCN vs LSTM)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { activeModelType = RecordingModelType.RFC },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (activeModelType == RecordingModelType.RFC) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent,
                                    contentColor = if (activeModelType == RecordingModelType.RFC) ElectricCyan else SlateText
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (activeModelType == RecordingModelType.RFC) ElectricCyan else CardBorder.copy(alpha = 0.1f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !isPredicting,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("RFC", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Button(
                                onClick = { activeModelType = RecordingModelType.TCN },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (activeModelType == RecordingModelType.TCN) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent,
                                    contentColor = if (activeModelType == RecordingModelType.TCN) ElectricCyan else SlateText
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (activeModelType == RecordingModelType.TCN) ElectricCyan else CardBorder.copy(alpha = 0.1f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !isPredicting,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("TCN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Button(
                                onClick = { activeModelType = RecordingModelType.LSTM },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (activeModelType == RecordingModelType.LSTM) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent,
                                    contentColor = if (activeModelType == RecordingModelType.LSTM) ElectricCyan else SlateText
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (activeModelType == RecordingModelType.LSTM) ElectricCyan else CardBorder.copy(alpha = 0.1f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !isPredicting,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("LSTM", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        // 2. Dropdown for model file selection
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = if (selectedModel.isEmpty()) "No models found" else selectedModel,
                                onValueChange = { selectedModel = it },
                                label = { Text("SELECT MODEL CONFIGURATION FILE (.json)") },
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isPredicting,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricCyan,
                                    unfocusedBorderColor = CardBorder.copy(alpha = 0.3f),
                                    focusedLabelColor = ElectricCyan,
                                    unfocusedLabelColor = SlateText,
                                    focusedTextColor = DarkSlateText,
                                    unfocusedTextColor = DarkSlateText
                                ),
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = ElectricCyan
                                    )
                                }
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable(enabled = !isPredicting) { showModelDropdown = !showModelDropdown }
                            )
                            DropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .background(MidnightBg)
                                    .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(8.dp))
                            ) {
                                if (availableModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No model files found (check /models)", color = SlateText) },
                                        onClick = { showModelDropdown = false }
                                    )
                                } else {
                                    availableModels.forEach { modelName ->
                                        DropdownMenuItem(
                                            text = { Text(modelName, color = DarkSlateText) },
                                            onClick = {
                                                selectedModel = modelName
                                                showModelDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (loadedRfcModel != null || loadedTcnModel != null || loadedLstmModel != null) {
                            val mType = when (activeModelType) {
                                RecordingModelType.RFC -> "Random Forest"
                                RecordingModelType.TCN -> "TCN Sequence"
                                RecordingModelType.LSTM -> "LSTM Sequence"
                            }
                            Text(
                                text = "✅ $mType model loaded. Classes: ${modelLabels.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraGreen
                            )
                        }

                        // Sign → Rest mode toggle (dynamic sequence models only).
                        if (activeModelType != RecordingModelType.RFC) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clayInset(ClayShapeSmall)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SIGN → REST MODE",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (signRestMode) AuroraGreen else DarkSlateText,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        text = "No prediction while signing — the full sign is " +
                                            "classified once when your hand returns to rest " +
                                            "(best for ISL signs)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateText
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Switch(
                                    checked = signRestMode,
                                    onCheckedChange = { signRestMode = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = AuroraGreen,
                                        checkedTrackColor = AuroraGreen.copy(alpha = 0.35f)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. Start/Stop Prediction button
                        val canPredict = isConnected && (loadedRfcModel != null || loadedTcnModel != null || loadedLstmModel != null)
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val buttonScale by infiniteTransition.animateFloat(
                            initialValue = 0.99f,
                            targetValue = 1.01f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = EaseInOutSine),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulseScale"
                        )
                        val activeBtnScale = if (canPredict && !isPredicting) buttonScale else 1f

                        Button(
                            onClick = { isPredicting = !isPredicting },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPredicting) CrimsonRed else ElectricCyan,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            enabled = if (isPredicting) true else canPredict,
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(activeBtnScale)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isPredicting) Icons.Default.ClearAll else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                                Text(
                                    text = if (isPredicting) "STOP PREDICTION" else "START PREDICTION",
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        if (!isConnected && !isPredicting) {
                            Text(
                                text = "🔌 CONNECT GLOVE HARDWARE TO START PREDICTION",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = CrimsonRed,
                                textAlign = TextAlign.Center
                            )
                        } else if (!canPredict && !isPredicting) {
                            Text(
                                text = "⚠️ Select a valid trained model file to enable predictions",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberCalibrate,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isPredicting) {
                            SensorHealthIndicator(viewModel)
                            Spacer(modifier = Modifier.height(16.dp))
                            GestureVisualizer(
                                gesture = predictedGesture,
                                color = gestureColor
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // 4. Predicted gesture label
                        Text(
                            text = "PREDICTED GESTURE LABEL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SlateText,
                            letterSpacing = 1.5.sp
                        )

                        Text(
                            text = if (isPredicting) remember(predictedGesture, voiceLanguage, translationRevision) {
                                GestureTranslator.translate(predictedGesture, voiceLanguage)
                            } else "PREDICTION OFFLINE",
                            fontSize = if (isPredicting) 48.sp else 24.sp,
                            fontWeight = FontWeight.Black,
                            color = gestureColor,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center
                        )
                        if (isPredicting && !voiceLanguage.isIdentity && !GestureLabels.isRest(predictedGesture)) {
                            Text(predictedGesture, color = SlateText, fontSize = 14.sp)
                        }

                        if (isPredicting) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "CONFIDENCE: %.1f%%", predictedConfidence),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = gestureColor.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "•",
                                    color = SlateText.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = "RATE: ${packetRate} HZ",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateText
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            // Live read of every label in real time (separate from the
                            // stable per-cycle result shown above).
                            Text(
                                text = "live: $liveLabel" +
                                    (if (liveConfidence > 0f) "  ${String.format(Locale.US, "%.0f", liveConfidence * 100)}%" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (atRest) SlateText else ElectricCyan
                            )
                        }
                    }
                }

                // Live stability tuning (while predicting; applies to RFC + TCN).
                if (isPredicting) {
                    Spacer(modifier = Modifier.height(16.dp))
                    StabilityTuningPanel(
                        confFloor = confFloor, onConfFloor = { confFloor = it },
                        margin = marginGate, onMargin = { marginGate = it },
                        restHoldMs = restHoldMs, onRestHoldMs = { restHoldMs = it },
                        minGestureMs = minGestureMs, onMinGestureMs = { minGestureMs = it },
                        commitHoldMs = commitHoldMs, onCommitHoldMs = { commitHoldMs = it },
                        idleRestMs = idleRestMs, onIdleRestMs = { idleRestMs = it },
                        top1 = engine.dbgTop1, top2 = engine.dbgTop2, top1Label = engine.dbgTop1Label
                    )
                }
            }
        }
    }
}

@Composable
private fun StabilityTuningPanel(
    confFloor: Float, onConfFloor: (Float) -> Unit,
    margin: Float, onMargin: (Float) -> Unit,
    restHoldMs: Float, onRestHoldMs: (Float) -> Unit,
    minGestureMs: Float, onMinGestureMs: (Float) -> Unit,
    commitHoldMs: Float, onCommitHoldMs: (Float) -> Unit,
    idleRestMs: Float, onIdleRestMs: (Float) -> Unit,
    top1: Float, top2: Float, top1Label: String
) {
    val marginNow = (top1 - top2).coerceAtLeast(0f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeMedium)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "STABILITY TUNING",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = AmberCalibrate,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            // Live readout — watch while signing / at rest to set the knobs.
            Text(
                text = "top1 $top1Label  ${String.format(Locale.US, "%.2f", top1)}   " +
                       "margin ${String.format(Locale.US, "%.2f", marginNow)}",
                style = MaterialTheme.typography.bodySmall,
                color = ElectricCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            TuneSlider("Conf floor", confFloor, 0f..0.9f, "%.2f", onConfFloor)
            TuneSlider("Margin (top1−top2)", margin, 0f..0.6f, "%.2f", onMargin)
            TuneSlider("Rest hold (ms)", restHoldMs, 150f..1500f, "%.0f", onRestHoldMs)
            TuneSlider("Min gesture (ms)", minGestureMs, 100f..1500f, "%.0f", onMinGestureMs)
            // Hold-to-speak interval: how long a sign must be held steadily before
            // it commits, so the user can chain signs without resting between them.
            TuneSlider("Hold to speak (ms)", commitHoldMs, 0f..1500f, "%.0f", onCommitHoldMs)
            // Hard-idle gate: no motion for this long ⇒ absolute REST, model ignored.
            TuneSlider("Idle → rest (ms)", idleRestMs, 400f..3000f, "%.0f", onIdleRestMs)
        }
    }
}

@Composable
private fun TuneSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    fmt: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = SlateText)
            Text(
                String.format(Locale.US, fmt, value),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = DarkSlateText
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun GestureVisualizer(gesture: String, color: Color, modifier: Modifier = Modifier) {
    val icon = when (gesture.uppercase().replace("_", " ")) {
        "HOW ARE YOU" -> Icons.Default.Face
        "HELLO", "HI" -> Icons.Default.Person
        "REST" -> Icons.Default.Info
        "YES" -> Icons.Default.CheckCircle
        "NO" -> Icons.Default.Warning
        else -> Icons.Default.Star
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(120.dp)
            .clayShadow(RoundedCornerShape(28.dp), ClayShadow, ClayHighlight, offset = 6.dp, blur = 14.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(color.copy(alpha = 0.14f))
            .border(BorderStroke(2.dp, color.copy(alpha = 0.35f)), RoundedCornerShape(28.dp))
            .padding(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = gesture,
            tint = color,
            modifier = Modifier.size(64.dp)
        )
    }
}
