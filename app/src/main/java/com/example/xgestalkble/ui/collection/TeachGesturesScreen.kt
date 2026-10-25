package com.example.xgestalkble.ui.collection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.xgestalkble.ble.BleConnectionState
import com.example.xgestalkble.ml.TrainingManager
import com.example.xgestalkble.theme.AmberCalibrate
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.ClayShapeLarge
import com.example.xgestalkble.theme.ClayShapeMedium
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.MidnightBg
import com.example.xgestalkble.theme.ObsidianBg
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.SubtleBg
import com.example.xgestalkble.theme.VioletAccent
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayInset
import com.example.xgestalkble.ui.components.SensorHealthIndicator
import com.example.xgestalkble.ui.main.BleScanDialog
import com.example.xgestalkble.ui.main.MainScreenViewModel
import com.example.xgestalkble.utils.GestureLabels
import com.example.xgestalkble.utils.PermissionUtils
import com.example.xgestalkble.utils.RecordingModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// TEACH MY SIGNS — the beginner path to gesture collection + training.
//
// Everything technical is decided for the user: one fixed dataset file, one
// model type (LSTM), fixed take length, fixed epochs. The wearer only ever
// does three things: connect the glove, record signs, press one TRAIN button.
// Power users still have the full DataCollectionScreen under "Advanced".
// ─────────────────────────────────────────────────────────────────────────────

// One shared dataset + model so the user never types a file name.
internal const val EASY_DATASET = "my_signs"          // TelemetryData/my_signs.csv
internal const val EASY_MODEL_NAME = "my_signs"       // models/my_signs_lstm.json

// One take = ~5 s of streaming at 50 Hz. Three takes per sign gives the LSTM
// hundreds of sequence windows per class across separate recording sessions
// (separate sessions are what teach boot-to-boot drift robustness).
private const val TAKE_TARGET = 250
private const val TAKES_NEEDED = 3
private const val SIGN_TARGET = TAKE_TARGET * TAKES_NEEDED
private const val TRAIN_EPOCHS = 60

private fun sanitizeSignName(raw: String): String =
    raw.trim()
        .replace(Regex("[,;\"\\\\]"), " ")   // CSV-hostile characters
        .replace(Regex("\\s+"), "_")
        .uppercase(Locale.US)

private enum class TakePhase { GET_READY, COUNTDOWN, RECORDING, SAVED }

@Composable
fun TeachGesturesScreen(
    onBackClick: () -> Unit,
    onTryModel: () -> Unit,
    onOpenAdvanced: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(context.applicationContext) }
    val connectionState by viewModel.connectionState.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val isConnected = connectionState == BleConnectionState.CONNECTED

    // Sample counts per label straight from my_signs.csv — the single source of
    // truth for progress bars, "ready" checkmarks and the train gate.
    var labelCounts by remember { mutableStateOf(analyzeCsvDataset(context, EASY_DATASET)) }
    fun refreshCounts() { labelCounts = analyzeCsvDataset(context, EASY_DATASET) }

    // Signs the user added this session that have no samples yet.
    val pendingSigns = remember { mutableStateListOf<String>() }
    var newSignName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    var recordingSign by remember { mutableStateOf<String?>(null) }
    var pendingDeleteSign by remember { mutableStateOf<String?>(null) }
    var showScanDialog by remember { mutableStateOf(false) }

    // Training state — one friendly line of status, no console. Owned by the
    // process-wide TrainingManager so the run (and its progress notification)
    // survives leaving this screen or closing the app.
    val isTraining by TrainingManager.isTraining.collectAsState()
    val trainProgress by TrainingManager.progress.collectAsState()
    val trainStatus by TrainingManager.statusText.collectAsState()
    val lastResult by TrainingManager.lastResult.collectAsState()
    // Only surface outcomes of runs started from this guided flow.
    val trainedAccuracy = lastResult?.takeIf { it.easyMode && it.success }?.accuracy
    val trainError = lastResult?.takeIf { it.easyMode && !it.success }?.message

    // All rest variants (REST, REST1, …) count toward the single rest pose row.
    val restCount = labelCounts.filterKeys { GestureLabels.isRest(it) }.values.sum()
    val signCounts = labelCounts.filterKeys { !GestureLabels.isRest(it) }
    val allSigns = (signCounts.keys.sorted() + pendingSigns.filter { it !in signCounts.keys })
    val restReady = restCount >= SIGN_TARGET
    val readySignCount = signCounts.values.count { it >= SIGN_TARGET }
    val canTrain = restReady && readySignCount >= 1 && !isTraining && !isRecording

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            showScanDialog = true
            viewModel.startScanning()
        }
    }
    LaunchedEffect(connectionState) {
        if (connectionState == BleConnectionState.CONNECTED) showScanDialog = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ObsidianBg, MidnightBg)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Training continues in the background, so only an in-progress
                // recording take pins the user to this screen.
                IconButton(onClick = onBackClick, enabled = recordingSign == null) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DarkSlateText)
                }
                Column {
                    Text(
                        "Teach My Signs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = DarkSlateText
                    )
                    Text(
                        "3 easy steps — no settings needed",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Visual journey strip so the whole path is understood at a glance.
                JourneyStrip(
                    gloveOk = isConnected,
                    recordOk = restReady && readySignCount >= 1,
                    trainOk = trainedAccuracy != null
                )

                // ── STEP 1 · CONNECT GLOVE ──────────────────────────────────
                StepCard(number = 1, title = "Connect your glove", done = isConnected) {
                    if (isConnected) {
                        Text(
                            "✅ Glove connected and ready!",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = AuroraGreen
                        )
                        Spacer(Modifier.height(12.dp))
                        SensorHealthIndicator(viewModel)
                    } else {
                        Text(
                            "Turn on your glove, then tap the button.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlateText
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (PermissionUtils.hasBluetoothPermissions(context)) {
                                    showScanDialog = true
                                    viewModel.startScanning()
                                } else {
                                    permissionsLauncher.launch(PermissionUtils.bluetoothPermissions)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("CONNECT GLOVE", fontWeight = FontWeight.Black, fontSize = 15.sp)
                        }
                    }
                }

                // ── STEP 2 · RECORD SIGNS ───────────────────────────────────
                StepCard(
                    number = 2,
                    title = "Record your signs",
                    done = restReady && readySignCount >= 1
                ) {
                    Text(
                        "Record the ✋ resting hand first, then each sign. " +
                            "Every sign needs $TAKES_NEEDED short recordings (5 seconds each).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateText
                    )
                    Spacer(Modifier.height(14.dp))

                    // Rest pose — always pinned first; the model can't work without it.
                    SignRow(
                        emoji = "✋",
                        name = "Resting hand",
                        subtitle = "Hand relaxed and still",
                        samples = restCount,
                        canRecord = isConnected && !isRecording && !isTraining,
                        onRecord = { recordingSign = GestureLabels.REST },
                        onDelete = null
                    )

                    allSigns.forEach { sign ->
                        Spacer(Modifier.height(10.dp))
                        SignRow(
                            emoji = "🤟",
                            name = sign,
                            subtitle = null,
                            samples = signCounts[sign] ?: 0,
                            canRecord = isConnected && !isRecording && !isTraining,
                            onRecord = { recordingSign = sign },
                            onDelete = { pendingDeleteSign = sign }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Add a new sign — the only typing in the whole flow.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = newSignName,
                            onValueChange = { newSignName = it; addError = null },
                            label = { Text("English meaning (example: I need water)") },
                            supportingText = { Text("Use a clear phrase. New models can generate regional translations in Model translations.") },
                            singleLine = true,
                            enabled = !isTraining,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricCyan,
                                unfocusedBorderColor = CardBorder.copy(alpha = 0.4f),
                                focusedLabelColor = ElectricCyan,
                                unfocusedLabelColor = SlateText,
                                focusedTextColor = DarkSlateText,
                                unfocusedTextColor = DarkSlateText
                            )
                        )
                        Button(
                            onClick = {
                                val name = sanitizeSignName(newSignName)
                                when {
                                    name.isEmpty() ->
                                        addError = "Type a name for your sign first."
                                    GestureLabels.isRest(name) ->
                                        addError = "That name is saved for the resting hand."
                                    name in allSigns ->
                                        addError = "You already have this sign."
                                    else -> {
                                        pendingSigns.add(name)
                                        newSignName = ""
                                        addError = null
                                    }
                                }
                            },
                            enabled = !isTraining,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VioletAccent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("ADD", fontWeight = FontWeight.Black)
                        }
                    }
                    addError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = CrimsonRed)
                    }
                }

                // ── STEP 3 · TRAIN ──────────────────────────────────────────
                StepCard(number = 3, title = "Teach the glove", done = trainedAccuracy != null) {
                    if (!canTrain && !isTraining && trainedAccuracy == null) {
                        Text(
                            when {
                                !restReady -> "First record the ✋ resting hand ($TAKES_NEEDED recordings)."
                                readySignCount < 1 -> "Now finish recording at least 1 sign."
                                else -> "Almost there!"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = AmberCalibrate
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    if (isTraining) {
                        Text(
                            trainStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { trainProgress },
                            color = ElectricCyan,
                            trackColor = CardBorder.copy(alpha = 0.15f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(trainProgress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = SlateText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    trainError?.let {
                        Text(
                            "⚠️ $it",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = CrimsonRed,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    trainedAccuracy?.let { acc ->
                        TrainResultPanel(accuracy = acc, onTryModel = onTryModel)
                        Spacer(Modifier.height(12.dp))
                    }

                    if (!isTraining) {
                        Button(
                            onClick = {
                                // Fixed LSTM recipe, run by TrainingManager in the
                                // background with a progress notification.
                                TrainingManager.startEasy(
                                    context = context.applicationContext,
                                    csvFileName = EASY_DATASET,
                                    outputModelName = EASY_MODEL_NAME,
                                    epochs = TRAIN_EPOCHS
                                )
                            },
                            enabled = canTrain,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AuroraGreen,
                                contentColor = Color.White,
                                disabledContainerColor = CardBorder.copy(alpha = 0.25f),
                                disabledContentColor = SlateText
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(60.dp)
                        ) {
                            Text(
                                if (trainedAccuracy != null) "🧠 TEACH AGAIN" else "🧠 TEACH THE GLOVE",
                                fontWeight = FontWeight.Black,
                                fontSize = 17.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Takes about a minute. You can close the app — training keeps going and shows progress in a notification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Quiet exit hatch for power users.
                Text(
                    "Advanced options (datasets, model types, settings)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = SlateText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = recordingSign == null) { onOpenAdvanced() }
                        .padding(vertical = 10.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        // ── Recording overlay — the guided take ────────────────────────────
        recordingSign?.let { sign ->
            RecordTakeOverlay(
                sign = sign,
                viewModel = viewModel,
                connected = isConnected,
                onTakeSaved = { refreshCounts() },
                onClose = { recordingSign = null; refreshCounts() }
            )
        }
    }

    if (showScanDialog) {
        BleScanDialog(
            scanResults = scanResults,
            onDeviceSelected = { result ->
                viewModel.connectDevice(result)
                showScanDialog = false
            },
            onDismissRequest = {
                viewModel.stopScanning()
                showScanDialog = false
            }
        )
    }

    pendingDeleteSign?.let { sign ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSign = null },
            title = { Text("Remove sign?", fontWeight = FontWeight.Bold, color = DarkSlateText) },
            text = {
                Text(
                    "Remove \"$sign\" and all its recordings? You cannot undo this.",
                    color = SlateText
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            deleteLabelFromCsv(context, EASY_DATASET, sign)
                        }
                        pendingSigns.remove(sign)
                        refreshCounts()
                        pendingDeleteSign = null
                    }
                }) { Text("REMOVE", color = CrimsonRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSign = null }) { Text("KEEP", color = SlateText) }
            },
            containerColor = MidnightBg
        )
    }
}

// Training itself lives in TrainingManager.runEasyLstm (same fixed LSTM recipe)
// so it survives app close and reports progress via the foreground service.

// ═══════════════════════ RECORDING OVERLAY ═══════════════════════

@Composable
private fun RecordTakeOverlay(
    sign: String,
    viewModel: MainScreenViewModel,
    connected: Boolean,
    onTakeSaved: () -> Unit,
    onClose: () -> Unit
) {
    val recordedCount by viewModel.recordedCount.collectAsState()
    val isRest = GestureLabels.isRest(sign)
    val displayName = if (isRest) "✋ Resting hand" else sign

    var phase by remember { mutableStateOf(TakePhase.GET_READY) }
    var countdown by remember { mutableStateOf(3) }
    var savedThisTake by remember { mutableStateOf(0) }

    // Countdown → start streaming to CSV.
    LaunchedEffect(phase) {
        if (phase == TakePhase.COUNTDOWN) {
            for (i in 3 downTo 1) {
                countdown = i
                delay(1000)
            }
            viewModel.startRecording(RecordingModelType.LSTM, EASY_DATASET, sign)
            phase = TakePhase.RECORDING
        }
    }
    // Auto-stop the take once enough frames were captured.
    LaunchedEffect(recordedCount, phase) {
        if (phase == TakePhase.RECORDING && recordedCount >= TAKE_TARGET) {
            savedThisTake = recordedCount
            viewModel.stopRecording()
            phase = TakePhase.SAVED
            onTakeSaved()
        }
    }
    // Safety net: never record forever if the stream stalls or sensors are gated.
    LaunchedEffect(phase) {
        if (phase == TakePhase.RECORDING) {
            delay(30_000)
            if (phase == TakePhase.RECORDING) {
                savedThisTake = recordedCount
                viewModel.stopRecording()
                phase = TakePhase.SAVED
                onTakeSaved()
            }
        }
    }
    // Glove dropped mid-take → save what we have and finish gracefully.
    LaunchedEffect(connected) {
        if (!connected && phase == TakePhase.RECORDING) {
            savedThisTake = recordedCount
            viewModel.stopRecording()
            phase = TakePhase.SAVED
            onTakeSaved()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg.copy(alpha = 0.96f))
            .clickable(enabled = false) {},   // swallow taps behind the overlay
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clayCard(ClayShapeLarge)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = DarkSlateText,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))

            when (phase) {
                TakePhase.GET_READY -> {
                    Text(
                        if (isRest) "Rest your hand.\nRelax. Do not move."
                        else "Get ready to do this sign\nagain and again, slowly.",
                        style = MaterialTheme.typography.titleMedium,
                        color = SlateText,
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { phase = TakePhase.COUNTDOWN },
                        enabled = connected,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CrimsonRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(60.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("START · 5 SECONDS", fontWeight = FontWeight.Black, fontSize = 17.sp)
                    }
                    if (!connected) {
                        Spacer(Modifier.height(8.dp))
                        Text("Glove is not connected.", color = CrimsonRed, fontWeight = FontWeight.Bold)
                    }
                }

                TakePhase.COUNTDOWN -> {
                    Text(
                        "$countdown",
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Black,
                        color = AmberCalibrate
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isRest) "Relax your hand…" else "Get your hand ready…",
                        style = MaterialTheme.typography.titleMedium,
                        color = SlateText
                    )
                }

                TakePhase.RECORDING -> {
                    val pulse = rememberInfiniteTransition(label = "recPulse")
                    val pulseScale by pulse.animateFloat(
                        initialValue = 0.92f,
                        targetValue = 1.08f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700, easing = EaseInOutSine),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "recPulse"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(84.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(CrimsonRed.copy(alpha = 0.18f))
                            .border(BorderStroke(3.dp, CrimsonRed), CircleShape)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(CrimsonRed)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (isRest) "Keep still…" else "Do the sign now!",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = CrimsonRed
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { (recordedCount.toFloat() / TAKE_TARGET).coerceIn(0f, 1f) },
                        color = CrimsonRed,
                        trackColor = CardBorder.copy(alpha = 0.15f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Stops by itself when the bar is full",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                }

                TakePhase.SAVED -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint = AuroraGreen,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (savedThisTake > 0) "Saved! Great job 🎉" else "Nothing saved — try again",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = if (savedThisTake > 0) AuroraGreen else CrimsonRed
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { phase = TakePhase.GET_READY },
                        enabled = connected,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text("RECORD AGAIN", fontWeight = FontWeight.Black)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            if (phase == TakePhase.GET_READY || phase == TakePhase.SAVED) {
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("CLOSE", color = SlateText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ═══════════════════════ SMALL BUILDING BLOCKS ═══════════════════════

@Composable
private fun JourneyStrip(gloveOk: Boolean, recordOk: Boolean, trainOk: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clayInset(ClayShapeMedium, SubtleBg)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        JourneyDot("🧤", "Connect", gloveOk)
        JourneyArrow()
        JourneyDot("🤟", "Record", recordOk)
        JourneyArrow()
        JourneyDot("🧠", "Train", trainOk)
        JourneyArrow()
        JourneyDot("🗣️", "Speak!", trainOk)
    }
}

@Composable
private fun JourneyDot(emoji: String, label: String, done: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (done) AuroraGreen.copy(alpha = 0.2f) else SubtleBg)
                .border(
                    BorderStroke(2.dp, if (done) AuroraGreen else CardBorder.copy(alpha = 0.5f)),
                    CircleShape
                )
        ) {
            Text(emoji, fontSize = 18.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (done) AuroraGreen else SlateText
        )
    }
}

@Composable
private fun JourneyArrow() {
    Text("→", fontSize = 16.sp, color = SlateText.copy(alpha = 0.5f))
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    done: Boolean,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().clayCard(ClayShapeLarge)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (done) AuroraGreen else ElectricCyan)
                ) {
                    if (done) {
                        Icon(
                            Icons.Default.Check,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            "$number",
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = DarkSlateText
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SignRow(
    emoji: String,
    name: String,
    subtitle: String?,
    samples: Int,
    canRecord: Boolean,
    onRecord: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val takesDone = (samples / TAKE_TARGET).coerceAtMost(TAKES_NEEDED)
    val ready = samples >= SIGN_TARGET
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clayInset(ClayShapeSmall, SubtleBg)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(emoji, fontSize = 22.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Black,
                    color = DarkSlateText
                )
                Text(
                    subtitle ?: if (ready) "Ready ✅" else "$takesDone of $TAKES_NEEDED recordings done",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ready) AuroraGreen else SlateText
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        "Remove $name",
                        tint = CrimsonRed.copy(alpha = 0.8f),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Button(
                onClick = onRecord,
                enabled = canRecord,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ready) SubtleBg else CrimsonRed,
                    contentColor = if (ready) SlateText else Color.White,
                    disabledContainerColor = CardBorder.copy(alpha = 0.2f),
                    disabledContentColor = SlateText
                ),
                border = if (ready) BorderStroke(1.dp, CardBorder.copy(alpha = 0.5f)) else null,
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    if (ready) "＋ MORE" else "● RECORD",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (samples.toFloat() / SIGN_TARGET).coerceIn(0f, 1f) },
            color = if (ready) AuroraGreen else ElectricCyan,
            trackColor = CardBorder.copy(alpha = 0.15f),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}

@Composable
private fun TrainResultPanel(accuracy: Float, onTryModel: () -> Unit) {
    val percent = (accuracy * 100).roundToInt()
    val stars = ((accuracy * 5).roundToInt()).coerceIn(1, 5)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clayInset(ClayShapeMedium, AuroraGreen.copy(alpha = 0.10f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🎉 Your glove learned your signs!", fontWeight = FontWeight.Black, fontSize = 16.sp, color = AuroraGreen)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(5) { i ->
                Icon(
                    Icons.Default.Star,
                    null,
                    tint = if (i < stars) AmberCalibrate else CardBorder.copy(alpha = 0.4f),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Understands you $percent% of the time",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = DarkSlateText
        )
        if (accuracy < 0.85f) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Tip: record more takes for each sign, then teach again.",
                style = MaterialTheme.typography.bodySmall,
                color = SlateText,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onTryModel,
            colors = ButtonDefaults.buttonColors(
                containerColor = VioletAccent,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Text("🗣️ TRY MY SIGNS NOW", fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
    }
}
