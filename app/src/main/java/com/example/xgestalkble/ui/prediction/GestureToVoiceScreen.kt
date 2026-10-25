package com.example.xgestalkble.ui.prediction

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.xgestalkble.ble.BleConnectionState
import com.example.xgestalkble.ml.RandomForestClassifier
import com.example.xgestalkble.ml.SessionCalibrator
import com.example.xgestalkble.theme.AmberCalibrate
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.MidnightBg
import com.example.xgestalkble.theme.ObsidianBg
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.ClayShapeLarge
import com.example.xgestalkble.theme.ClayShapeMedium
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.ClayShadow
import com.example.xgestalkble.theme.ClayHighlight
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayInset
import com.example.xgestalkble.theme.clayShadow
import com.example.xgestalkble.ui.main.MainScreenViewModel
import com.example.xgestalkble.utils.AssetLoaderUtils
import com.example.xgestalkble.utils.TextToSpeechHelper
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.LanguageConfig
import java.io.File
import java.util.Locale

// Constants for Gesture mappings
private val EMERGENCY_MAP = mapOf(
    "0" to "HI", "1" to "I AM", "2" to "VIMUN", "3" to "FOOD", "4" to "WATER", "5" to "THANK YOU",
    "6" to "YOU", "7" to "REST", "8" to "WELCOME", "9" to "RIGHT", "10" to "HELP", "11" to "PLEASE",
    "12" to "SORRY", "13" to "YES", "14" to "NO", "15" to "GOOD", "16" to "BAD", "17" to "MORNING",
    "18" to "AFTERNOON", "19" to "EVENING", "20" to "NIGHT", "21" to "TOILET", "22" to "PAIN",
    "23" to "MEDICINE", "24" to "DOCTOR", "25" to "EMERGENCY", "26" to "FAMILY", "27" to "FRIEND",
    "28" to "HAPPY", "29" to "SAD", "30" to "TIRED", "31" to "HUNGRY"
)

private val digitLabelToId = mapOf(
    "ZERO" to "0", "ONE" to "1", "TWO" to "2", "THREE" to "3", "FOUR" to "4",
    "FIVE" to "5", "SIX" to "6", "SEVEN" to "7", "EIGHT" to "8", "NINE" to "9",
    "0" to "0", "1" to "1", "2" to "2", "3" to "3", "4" to "4",
    "5" to "5", "6" to "6", "7" to "7", "8" to "8", "9" to "9"
)

private val digitIdToText = mapOf(
    "0" to "Zero", "1" to "One", "2" to "Two", "3" to "Three", "4" to "Four",
    "5" to "Five", "6" to "Six", "7" to "Seven", "8" to "Eight", "9" to "Nine"
)

enum class GestureCategory(val displayName: String, val assetsFolder: String) {
    DIGITS("Digits", "numbers"),
    EMERGENCY("Emergency signs", "emergency"),
    PUBLIC_SIGNS("Public signs", "public-signs")
}

enum class ScreenState {
    SELECTION,
    PREDICTION
}

data class GestureItem(
    val id: String,
    val text: String,
    val imageName: String,
    val audioName: String?
)

@Composable
fun GestureToVoiceScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var screenState by remember { mutableStateOf(ScreenState.SELECTION) }
    var selectedCategory by remember { mutableStateOf(GestureCategory.DIGITS) }

    when (screenState) {
        ScreenState.SELECTION -> {
            GestureToVoiceSelectionScreen(
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                onGoForPrediction = { screenState = ScreenState.PREDICTION },
                onBackClick = onBackClick,
                modifier = modifier
            )
        }
        ScreenState.PREDICTION -> {
            GestureToVoicePredictionScreen(
                category = selectedCategory,
                onBackClick = { screenState = ScreenState.SELECTION },
                modifier = modifier
            )
        }
    }
}

@Composable
fun GestureToVoiceSelectionScreen(
    selectedCategory: GestureCategory,
    onCategorySelected: (GestureCategory) -> Unit,
    onGoForPrediction: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ObsidianBg, MidnightBg)))
            .padding(20.dp)
    ) {
        // HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to dashboard",
                    tint = DarkSlateText
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "GESTURE TO VOICE & IMAGE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = DarkSlateText,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Select a translation mode to begin",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // CATEGORY CARDS LIST
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GestureCategory.values().forEach { category ->
                val isSelected = selectedCategory == category
                val glowColor by animateColorAsState(
                    targetValue = if (isSelected) ElectricCyan else CardBorder.copy(alpha = 0.2f),
                    label = "borderGlow"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clayCard(ClayShapeMedium)
                        .then(if (isSelected) Modifier.border(BorderStroke(2.dp, glowColor), ClayShapeMedium) else Modifier)
                        .clickable { onCategorySelected(category) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Badge / Icon container
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clayShadow(CircleShape, ClayShadow, ClayHighlight, offset = 4.dp, blur = 8.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) ElectricCyan.copy(alpha = 0.16f) else CardBg)
                        ) {
                            when (category) {
                                GestureCategory.DIGITS -> {
                                    Text(
                                        text = "123",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 12.sp,
                                        color = if (isSelected) ElectricCyan else SlateText
                                    )
                                }
                                GestureCategory.EMERGENCY -> {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = if (isSelected) ElectricCyan else SlateText,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                GestureCategory.PUBLIC_SIGNS -> {
                                    Icon(
                                        imageVector = Icons.Default.RecordVoiceOver,
                                        contentDescription = null,
                                        tint = if (isSelected) ElectricCyan else SlateText,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // Text content
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = category.displayName.uppercase(Locale.US),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) ElectricCyan else DarkSlateText
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when (category) {
                                    GestureCategory.DIGITS -> "Translate glove configurations for numbers 0-9 into spoken digits and visuals."
                                    GestureCategory.EMERGENCY -> "Translate critical emergency requests like HELP, WATER, TOILET, PAIN, and DOCTOR."
                                    GestureCategory.PUBLIC_SIGNS -> "Translate common public interactions like HELLO, MY NAME IS, and GOOD MORNING."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateText,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ACTION BUTTON
        Button(
            onClick = onGoForPrediction,
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "GO FOR PREDICTION",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
fun GestureToVoicePredictionScreen(
    category: GestureCategory,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Initialize TTS
    LaunchedEffect(Unit) {
        TextToSpeechHelper.init(context.applicationContext)
    }

    // Load available manual items
    val items = remember(category) { getGestureItemsForCategory(category) }
    var currentIndex by remember { mutableStateOf(-1) } // Start at -1 (default/idle state)

    // BLE Glove connection and telemetry data setup
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(context.applicationContext) }
    val connectionState by viewModel.connectionState.collectAsState()
    val dataPacket by viewModel.dataPacket.collectAsState()
    val isConnected = connectionState == BleConnectionState.CONNECTED

    var audioMuted by remember { mutableStateOf(false) }

    // Load digits1_rfc model for Digits mode
    var rfcModel by remember { mutableStateOf<RandomForestClassifier?>(null) }
    // Per-session drift compensation (survives a glove power-cycle).
    val calibrator = remember { SessionCalibrator() }
    LaunchedEffect(category) {
        if (category == GestureCategory.DIGITS) {
            try {
                val file = File(context.getExternalFilesDir("models"), "digits1_rfc.json")
                if (file.exists()) {
                    rfcModel = RandomForestClassifier.loadFromFile(file)
                    calibrator.setReference(rfcModel!!.restBaseline)
                    android.util.Log.d("GestureToVoice", "digits1_rfc.json loaded successfully")
                } else {
                    android.util.Log.e("GestureToVoice", "digits1_rfc.json model file not found")
                }
            } catch (e: Exception) {
                android.util.Log.e("GestureToVoice", "Failed to load digits1_rfc: ${e.message}")
            }
        }
    }

    // Live predicted states
    var liveLabel by remember { mutableStateOf("") }
    val predictionWindow = remember { ArrayDeque<String>(20) }
    val windowSize = 20
    var confirmedLabel by remember { mutableStateOf("") }
    var lastSpokenLabel by remember { mutableStateOf("") }
    var lastSpeakTime by remember { mutableStateOf(0L) }
    var stableCandidate by remember { mutableStateOf("") }
    var consecutiveCount by remember { mutableStateOf(0) }

    // BLE Live prediction logic (runs when connected and in Digits mode)
    LaunchedEffect(dataPacket, isConnected, category) {
        if (isConnected && dataPacket != null && category == GestureCategory.DIGITS) {
            val packet = dataPacket!!
            val features = calibrator.process(packet.normalizedFeatures)
            val classifier = rfcModel
            if (classifier != null) {
                val (rawPrediction, confidence) = classifier.predictWithConfidence(features)

                // 1. Thresholds Tuning for RFC
                val thresholdToUse = 15 // 75% of 20
                val minConfidence = 0.40f

                // v3.1 firmware has no flexNorm channel — the old open-hand
                // rest-gate is gone; the model + majority vote handle REST.
                val adjustedPrediction = rawPrediction
                val adjustedConfidence = confidence

                predictionWindow.addLast(adjustedPrediction)
                if (predictionWindow.size > windowSize) {
                    predictionWindow.removeFirst()
                }

                val counts = predictionWindow.groupingBy { it }.eachCount()
                val maxEntry = counts.maxByOrNull { it.value }
                val dominantPrediction = maxEntry?.key
                val dominantCount = maxEntry?.value ?: 0

                val isConfirmed = dominantPrediction != null &&
                        dominantCount >= thresholdToUse &&
                        (dominantPrediction == "REST" || adjustedConfidence >= minConfidence)

                if (isConfirmed && dominantPrediction != null) {
                    // Transition Debounce Filter: Require new gesture state to be stable for 5 consecutive frames
                    if (dominantPrediction != confirmedLabel) {
                        if (dominantPrediction == stableCandidate) {
                            consecutiveCount++
                        } else {
                            stableCandidate = dominantPrediction
                            consecutiveCount = 1
                        }

                        if (consecutiveCount >= 5) {
                            confirmedLabel = dominantPrediction
                            liveLabel = dominantPrediction
                            consecutiveCount = 0
                            stableCandidate = ""
                        }
                    } else {
                        consecutiveCount = 0
                        stableCandidate = ""
                    }
                } else {
                    if (consecutiveCount > 0) {
                        consecutiveCount--
                    }
                }
            }
        } else if (!isConnected) {
            liveLabel = ""
            confirmedLabel = ""
            predictionWindow.clear()
            stableCandidate = ""
            consecutiveCount = 0
        }
    }

    // Auto TTS speaker trigger for live predictions
    LaunchedEffect(liveLabel, audioMuted, LanguageConfig.voiceLanguage) {
        if (liveLabel.isNotEmpty() && !audioMuted && category == GestureCategory.DIGITS) {
            val norm = liveLabel.trim().uppercase(Locale.US)
            if (norm != "REST") {
                val curTime = System.currentTimeMillis()
                val id = digitLabelToId[norm]
                val speakText = if (id != null) digitIdToText[id] ?: liveLabel else liveLabel
                if (speakText != lastSpokenLabel || curTime - lastSpeakTime >= 1500L) {
                    GestureTranslator.speak(speakText, LanguageConfig.voiceLanguage)
                    lastSpokenLabel = speakText
                    lastSpeakTime = curTime
                }
            }
        }
    }

    // Current displayed gesture item
    val currentItem = if (isConnected && category == GestureCategory.DIGITS) {
        val norm = liveLabel.trim().uppercase(Locale.US)
        val id = digitLabelToId[norm]
        if (id != null) {
            GestureItem(
                id = id,
                text = digitIdToText[id] ?: liveLabel,
                imageName = "$id.png",
                audioName = null
            )
        } else {
            null // Default Standby
        }
    } else {
        if (currentIndex >= 0) items.getOrNull(currentIndex) else null
    }

    // Stop audio player on dispose
    DisposableEffect(Unit) {
        onDispose {
            AssetLoaderUtils.stopAssetAudio()
        }
    }

    // Manual Simulation auto audio trigger
    LaunchedEffect(currentItem, audioMuted, LanguageConfig.voiceLanguage) {
        if (!isConnected && !audioMuted && currentItem != null) {
            if (category == GestureCategory.DIGITS) {
                GestureTranslator.speak(currentItem.text, LanguageConfig.voiceLanguage)
            } else if (!LanguageConfig.voiceLanguage.isIdentity && currentItem.text != "REST" && currentItem.text != "REST / IDLE") {
                GestureTranslator.speak(currentItem.text, LanguageConfig.voiceLanguage)
            } else if (currentItem.audioName != null) {
                val audioPath = "audios/${category.assetsFolder}/${currentItem.audioName}"
                AssetLoaderUtils.playAudioFromAssets(context, audioPath)
            }
        }
    }

    val imageAssetPath = if (currentItem != null) {
        "images/${category.assetsFolder}/${currentItem.imageName}"
    } else {
        "images/${category.assetsFolder}/default.png"
    }

    val displayText = currentItem?.text ?: "Waiting for gesture..."
    val audioFileName = currentItem?.audioName

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ObsidianBg, MidnightBg)))
            .padding(16.dp)
    ) {
        // HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to selection",
                        tint = DarkSlateText
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "${category.displayName.uppercase(Locale.US)} TRANSLATION",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = DarkSlateText,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Active translation cards view",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                }
            }

            // Connection Badge showing actual BLE connection state
            Box(
                modifier = Modifier
                    .clayCard(ClayShapeSmall)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
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

        Spacer(modifier = Modifier.height(16.dp))

        // CARDS LIST (SCROLLABLE)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. IMAGE CARD (LARGE WITH PERFECT CURVES)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clayCard(ClayShapeLarge)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "GESTURE ILLUSTRATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SlateText,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val fallbackAssetPath = "images/${category.assetsFolder}/default.png"
                    val painter = rememberAssetImagePainter(context, imageAssetPath, fallbackAssetPath)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MidnightBg)
                            .border(BorderStroke(1.dp, CardBorder.copy(alpha = 0.5f)), RoundedCornerShape(16.dp))
                    ) {
                        if (painter != null) {
                            Image(
                                painter = painter,
                                contentDescription = displayText,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = SlateText.copy(alpha = 0.3f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }
            }

            // 2. TEXT CARD (DOWN SIDE OF IMAGE CARD WITH PERFECT CURVES)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clayCard(ClayShapeLarge)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "TEXT TRANSLATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SlateText,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = displayText.uppercase(Locale.US),
                        fontSize = if (displayText.length > 10) 22.sp else 32.sp,
                        fontWeight = FontWeight.Black,
                        color = DarkSlateText,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 3. AUDIO CARD (SMALLER WITH PERFECT CURVES)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clayCard(ClayShapeLarge)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val audioEnabled = (category == GestureCategory.DIGITS && displayText != "Waiting for gesture...") ||
                                (category != GestureCategory.DIGITS && audioFileName != null)

                        IconButton(
                            onClick = {
                                if (category == GestureCategory.DIGITS) {
                                    GestureTranslator.speak(displayText, LanguageConfig.voiceLanguage)
                                } else if (!LanguageConfig.voiceLanguage.isIdentity) {
                                    GestureTranslator.speak(displayText, LanguageConfig.voiceLanguage)
                                } else if (audioFileName != null) {
                                    val audioPath = "audios/${category.assetsFolder}/$audioFileName"
                                    AssetLoaderUtils.playAudioFromAssets(context, audioPath)
                                }
                            },
                            enabled = audioEnabled && !audioMuted,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (audioEnabled && !audioMuted) ElectricCyan.copy(alpha = 0.15f) else ObsidianBg)
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (audioEnabled && !audioMuted) ElectricCyan else CardBorder.copy(alpha = 0.5f)
                                    ), CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Play spoken audio",
                                tint = if (audioEnabled && !audioMuted) ElectricCyan else SlateText.copy(alpha = 0.5f)
                            )
                        }

                        Column {
                            Text(
                                text = "VOICE OUTPUT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = SlateText,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = if (audioMuted) "MUTED" else if (audioEnabled) "TAP TO SPEAK" else "SILENT",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (audioEnabled && !audioMuted) ElectricCyan else SlateText,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Mute Toggle
                    IconButton(onClick = {
                        audioMuted = !audioMuted
                        if (audioMuted) {
                            AssetLoaderUtils.stopAssetAudio()
                            TextToSpeechHelper.shutdown() // Stops active speech
                            TextToSpeechHelper.init(context.applicationContext) // Re-initialize for future use
                        }
                    }) {
                        Icon(
                            imageVector = if (audioMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Mute toggle",
                            tint = if (audioMuted) CrimsonRed else ElectricCyan
                        )
                    }
                }
            }

            // 4. MANUAL SIMULATION PANEL WITH PERFECT CURVES (Visible when not actively receiving live data)
            if (!isConnected || category != GestureCategory.DIGITS) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clayCard(ClayShapeMedium)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "PREVIEW GESTURE SIMULATOR",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SlateText,
                            letterSpacing = 1.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { if (currentIndex > -1) currentIndex-- },
                                enabled = currentIndex > -1
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "Previous Gesture",
                                    tint = if (currentIndex > -1) ElectricCyan else SlateText.copy(alpha = 0.3f),
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Text(
                                text = if (currentIndex == -1) "DEFAULT STANDBY" else "GESTURE ${currentIndex + 1} OF ${items.size}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Black,
                                color = DarkSlateText
                            )

                            IconButton(
                                onClick = { if (currentIndex < items.size - 1) currentIndex++ },
                                enabled = currentIndex < items.size - 1
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Next Gesture",
                                    tint = if (currentIndex < items.size - 1) ElectricCyan else SlateText.copy(alpha = 0.3f),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

fun getGestureItemsForCategory(category: GestureCategory): List<GestureItem> {
    return when (category) {
        GestureCategory.DIGITS -> {
            (0..9).map { i ->
                GestureItem(
                    id = i.toString(),
                    text = when (i) {
                        0 -> "Zero"
                        1 -> "One"
                        2 -> "Two"
                        3 -> "Three"
                        4 -> "Four"
                        5 -> "Five"
                        6 -> "Six"
                        7 -> "Seven"
                        8 -> "Eight"
                        9 -> "Nine"
                        else -> ""
                    },
                    imageName = "$i.png",
                    audioName = null // Handled via TextToSpeechHelper
                )
            }
        }
        GestureCategory.EMERGENCY -> {
            (0..31).map { i ->
                val label = EMERGENCY_MAP[i.toString()] ?: "REST"
                GestureItem(
                    id = i.toString(),
                    text = label,
                    imageName = "$i.png",
                    audioName = "$i.mp3"
                )
            }
        }
        GestureCategory.PUBLIC_SIGNS -> {
            listOf(
                GestureItem("0", "REST / IDLE", "default.png", null),
                GestureItem("1", "HELLO", "1.png", "1.mp3"),
                GestureItem("2", "MY NAME IS", "2.png", "2.mp3"),
                GestureItem("3", "I AM INDIAN", "3.png", "3.mp3"),
                GestureItem("4", "I NEED HELP", "4.png", "4.mp3"),
                GestureItem("5", "HOW ARE YOU", "5.png", "5.mp3"),
                GestureItem("6", "GOOD MORNING", "6.png", "6.mp3"),
                GestureItem("7", "I AM ALONE", "7.png", "default.mp3"),
                GestureItem("9", "I AM HUNGRY", "9.png", "default.mp3"),
                GestureItem("10", "I AM SORRY", "10.png", "default.mp3")
            )
        }
    }
}

@Composable
fun rememberAssetImagePainter(context: Context, assetPath: String, fallbackPath: String? = null): BitmapPainter? {
    var imageBitmap by remember(assetPath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(assetPath) {
        try {
            var bitmap = AssetLoaderUtils.loadBitmapFromAssets(context, assetPath)
            if (bitmap == null && fallbackPath != null) {
                bitmap = AssetLoaderUtils.loadBitmapFromAssets(context, fallbackPath)
            }
            imageBitmap = bitmap?.asImageBitmap()
        } catch (e: Exception) {
            android.util.Log.e("rememberAssetImagePainter", "Painter error: ${e.message}")
            imageBitmap = null
        }
    }

    return imageBitmap?.let { BitmapPainter(it) }
}
