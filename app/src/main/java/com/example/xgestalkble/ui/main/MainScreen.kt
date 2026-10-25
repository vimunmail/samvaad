package com.example.xgestalkble.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SignLanguage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.example.xgestalkble.R
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.xgestalkble.Conversation
import com.example.xgestalkble.TextToAudio
import com.example.xgestalkble.ble.BleConnectionState
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
import com.example.xgestalkble.theme.ThemeConfig
import com.example.xgestalkble.theme.SkyBlue
import com.example.xgestalkble.theme.ClayShapeLarge
import com.example.xgestalkble.theme.ClayShapeXL
import com.example.xgestalkble.theme.ClayShapeMedium
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.ClayShapeChip
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayInset
import com.example.xgestalkble.theme.clayShadow
import com.example.xgestalkble.theme.ClayShadow
import com.example.xgestalkble.theme.ClayHighlight
import com.example.xgestalkble.Tools
import com.example.xgestalkble.SoundAlert
import com.example.xgestalkble.ui.components.LanguageSettingsDialog
import com.example.xgestalkble.utils.LanguageConfig
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.PermissionUtils
import com.example.xgestalkble.utils.TextToSpeechHelper

data class FileToDelete(val name: String, val type: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(context.applicationContext) }
    val connectionState by viewModel.connectionState.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val packetRate by viewModel.packetRate.collectAsState()
    val batteryPercent by viewModel.batteryPercent.collectAsState()

    var showScanDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showRecalibrate by remember { mutableStateOf(false) }

    // Auto-scan on startup for auto-pairing
    LaunchedEffect(Unit) {
        if (PermissionUtils.hasBluetoothPermissions(context) && connectionState == BleConnectionState.DISCONNECTED) {
            viewModel.logInfo("[AUTO-PAIR] App started. Scanning for Samvaad Glove in background...")
            viewModel.startScanning()
        }
    }

    // Automatically close scan dialog when connected
    LaunchedEffect(connectionState) {
        if (connectionState == BleConnectionState.CONNECTED) {
            showScanDialog = false
        }
    }

    // Multi-Permission Launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            viewModel.logInfo("System Permissions Granted. Opening Scan HUD.")
            showScanDialog = true
            viewModel.startScanning()
        } else {
            viewModel.logInfo("Fatal: Bluetooth scanning requires permissions.")
        }
    }

    val onBluetoothAction = {
        if (connectionState == BleConnectionState.CONNECTED) {
            viewModel.disconnectDevice()
        } else {
            if (PermissionUtils.hasBluetoothPermissions(context)) {
                showScanDialog = true
                viewModel.startScanning()
            } else {
                permissionsLauncher.launch(PermissionUtils.bluetoothPermissions)
            }
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

    if (showLanguageDialog) {
        LanguageSettingsDialog(onDismiss = { showLanguageDialog = false })
    }

    if (showRecalibrate) {
        RecalibrationDialog(
            connected = connectionState == BleConnectionState.CONNECTED,
            onStart = { viewModel.recalibrateGlove() },
            onDismiss = { showRecalibrate = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(ObsidianBg, MidnightBg)
                )
            )
    ) {
        // ─── Top App Header ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clayShadow(CircleShape, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                        .clip(CircleShape)
                        .background(CardBg)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.samvaad_logo),
                        contentDescription = "Samvaad Logo",
                        modifier = Modifier.fillMaxSize().padding(3.dp)
                    )
                }
                Text(
                    text = "Samvaad",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = DarkSlateText,
                    letterSpacing = (-0.3).sp
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderActionButton(
                    icon = if (ThemeConfig.isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    tint = if (ThemeConfig.isDarkTheme) AmberCalibrate else SkyBlue,
                    contentDescription = "Toggle light/dark theme",
                    onClick = { ThemeConfig.toggle(context) }
                )
                HeaderActionButton(
                    icon = Icons.Default.Tune,
                    tint = SlateText,
                    contentDescription = "Open tools and settings",
                    onClick = { onItemClick(Tools) }
                )
            }
        }

        // ─── Scrollable Body ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Hero device connection card
            DeviceStatusCard(
                connectionState = connectionState,
                deviceName = connectedDeviceName,
                packetRate = packetRate,
                batteryPercent = batteryPercent,
                onAction = onBluetoothAction
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Dedicated Calibration Card
            RecalibrateGloveCard(onClick = { showRecalibrate = true })

            Spacer(modifier = Modifier.height(16.dp))

            // Primary call-to-action: open the two-way conversation chat
            StartConversationCard(onClick = { onItemClick(Conversation) })

            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel(text = "TALK INSTANTLY")
            Spacer(modifier = Modifier.height(12.dp))

            // Quick spoken phrases — one tap to speak, no signing needed.
            QuickPhrasesCard()

            Spacer(modifier = Modifier.height(12.dp))

            SecondaryBanner(
                icon = Icons.Default.RecordVoiceOver,
                title = "Text Translation & Audio",
                description = "Translate English into regional text, listen and create audio clips.",
                onClick = { onItemClick(TextToAudio) }
            )


            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel(text = "LISTEN")
            Spacer(modifier = Modifier.height(12.dp))

            // "Environmental ears" — the phone listens and alerts on important
            // sounds (doorbell, knock, alarm…) via vibration, flash and strobe.
            SoundAlertCard(onClick = { onItemClick(SoundAlert) })

            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel(text = "LANGUAGE")
            Spacer(modifier = Modifier.height(12.dp))

            // Quick language control — the wearer needs this front-and-center.
            LanguageSummaryCard(onClick = { showLanguageDialog = true })

            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel(text = "MORE")
            Spacer(modifier = Modifier.height(12.dp))

            // Everything technical lives behind one calm entry point so the home
            // screen stays focused on talking.
            SecondaryBanner(
                icon = Icons.Default.Tune,
                title = "Tools & Settings",
                description = "Sign translation, live prediction, model training, files and device tools.",
                onClick = { onItemClick(Tools) }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ─── Reusable pieces ──────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickPhrasesCard() {
    val phrases = listOf(
        "Hello" to "👋 Hello",
        "Thank you" to "🙏 Thank you",
        "Yes" to "✅ Yes",
        "No" to "❌ No",
        "I am deaf" to "🦻 I am deaf",
        "Please type to me" to "⌨️ Please type to me",
        "Water, please" to "💧 Water, please",
        "Good morning" to "🌅 Good morning",
        "Sorry" to "😔 Sorry",
        "Please wait" to "✋ Please wait",
        "How much?" to "💰 How much?",
        "Where is the toilet?" to "🚻 Where is the toilet?",
        "Call a doctor" to "🩺 Call a doctor",
        "I need help" to "🚨 I need help!"
    )

    // Small by default — tap the header to reveal the full command list.
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation = if (expanded) 180f else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeLarge)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Compact header row — the whole card collapses to just this.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clayShadow(RoundedCornerShape(15.dp), ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Brush.linearGradient(listOf(ElectricCyan, SkyBlue)))
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Quick Phrases",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = DarkSlateText
                    )
                    Text(
                        text = if (expanded) "Tap a phrase to speak it out loud" else "Tap to see more commands",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = ElectricCyan,
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(chevronRotation)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for ((text, display) in phrases) {
                            val isEmergency = text == "I need help"
                            val bgCol = if (isEmergency) CrimsonRed.copy(alpha = 0.16f) else CardBg
                            val textCol = if (isEmergency) CrimsonRed else DarkSlateText

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clayShadow(ClayShapeChip, ClayShadow, ClayHighlight, offset = 4.dp, blur = 10.dp)
                                    .clip(ClayShapeChip)
                                    .background(bgCol)
                                    .clickable {
                                        GestureTranslator.speak(text, LanguageConfig.voiceLanguage)
                                    }
                                    .padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    text = display,
                                    color = textCol,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageSummaryCard(onClick: () -> Unit) {
    val voice = LanguageConfig.voiceLanguage
    val stt = LanguageConfig.sttLanguage
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeLarge)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(18.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clayShadow(RoundedCornerShape(15.dp), ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(ElectricCyan, SkyBlue)))
            ) {
                Icon(Icons.Default.Language, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LanguageMiniRow(Icons.AutoMirrored.Filled.VolumeUp, "Sign → Voice", voice.nativeName)
                LanguageMiniRow(Icons.Default.Hearing, "Speech → Text", stt.nativeName)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(SubtleBg)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Change languages", tint = ElectricCyan, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun LanguageMiniRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = SlateText, modifier = Modifier.size(15.dp))
        Text(label, fontSize = 12.sp, color = SlateText, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DarkSlateText)
    }
}

@Composable
internal fun SecondaryBanner(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeLarge)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clayInset(ClayShapeMedium)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SlateText,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = DarkSlateText
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = SlateText.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SoundAlertCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeLarge)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            AuroraGreen.copy(alpha = 0.18f),
                            ElectricCyan.copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clayShadow(RoundedCornerShape(16.dp), ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(AuroraGreen, ElectricCyan)))
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sound Alerts",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        color = DarkSlateText
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Your phone listens and alerts you to doorbells, knocks & alarms.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = SlateText
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clayShadow(ClayShapeChip, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                        .clip(ClayShapeChip)
                        .background(AuroraGreen)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("LIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RecalibrateGloveCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeLarge)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            AmberCalibrate.copy(alpha = 0.18f),
                            ElectricCyan.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clayShadow(RoundedCornerShape(16.dp), ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(AmberCalibrate, ElectricCyan)))
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Recalibrate Glove",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        color = DarkSlateText
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Re-teach finger open & fist ranges for accurate gestures.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        color = SlateText
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clayShadow(ClayShapeChip, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                        .clip(ClayShapeChip)
                        .background(AmberCalibrate)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("START", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StartConversationCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeXL)
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    listOf(ElectricCyan.copy(alpha = 0.7f), SkyBlue.copy(alpha = 0.7f))
                ),
                shape = ClayShapeXL
            )
            .clip(ClayShapeXL)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    // SOLID bold blue→sky gradient (fixed colors so it stays vivid
                    // in BOTH light & dark themes and white text always reads).
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1457C7), // deep blue
                            Color(0xFF1E8FD6), // mid blue
                            Color(0xFF18A7E8)  // sky blue
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // ── Header ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .clayShadow(RoundedCornerShape(18.dp), ClayShadow, ClayHighlight, offset = 5.dp, blur = 11.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(ElectricCyan, SkyBlue)))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Start Conversation",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Translate smart-glove signs directly to synthesized voice and regional text.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── Live sign → voice → regional-text showcase ────────────
                SignToSpeechShowcase()

                Spacer(modifier = Modifier.height(20.dp))

                // ── Call to action ────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Open conversation",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clayShadow(CircleShape, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                            .clip(CircleShape)
                            .background(ElectricCyan)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated home-screen showcase of the core flow: a sensor glove "signs"
 * (animated avatar + radiating signal), the signal flows across an animated
 * connector into a live voice waveform, and finally surfaces as text that
 * cycles through Indian regional-language greetings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignToSpeechShowcase() {
    // Greeting in script + the language name, cycled for the "→ Text" stage.
    // All 22 official (scheduled) Indian languages, each in its own script.
    val greetings = remember {
        listOf(
            "नमस्ते" to "Hindi",
            "নমস্কার" to "Bengali",
            "வணக்கம்" to "Tamil",
            "నమస్కారం" to "Telugu",
            "ನಮಸ್ಕಾರ" to "Kannada",
            "നമസ്കാരം" to "Malayalam",
            "નમસ્તે" to "Gujarati",
            "ਸਤ ਸ੍ਰੀ ਅਕਾਲ" to "Punjabi",
            "नमस्कार" to "Marathi",
            "ନମସ୍କାର" to "Odia",
            "নমস্কাৰ" to "Assamese",
            "السلام علیکم" to "Urdu",
            "नमो नमः" to "Sanskrit",
            "سلام" to "Sindhi",
            "नमस्ते" to "Nepali",
            "नमस्कार" to "Konkani",
            "ꯈꯨꯔꯨꯝꯖꯔꯤ" to "Manipuri",
            "खुलुमबाय" to "Bodo",
            "नमस्कार" to "Dogri",
            "प्रणाम" to "Maithili",
            "ᱡᱳᱦᱟᱨ" to "Santali",
            "آداب" to "Kashmiri"
        )
    }
    var idx by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1600)
            idx = (idx + 1) % greetings.size
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(215.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Stage 1 — Waving Avatar in a capsule (video fills the whole frame)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(140.dp, 176.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MidnightBg.copy(alpha = 0.5f),
                                CardBg.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .border(
                        width = 3.dp,
                        color = Color.White,
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                AvatarWave(modifier = Modifier.fillMaxSize())
            }
            Spacer(modifier = Modifier.height(6.dp))
            StageLabel(text = "SIGN GESTURE", color = Color.White)
        }

        // Animated signal flowing from the glove to the output
        FlowDots(modifier = Modifier.width(24.dp))

        // Stage 2 & 3 — voice waveform and the cycling regional text
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Sign → Voice (Speech output indicator)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                VoiceWave(color = Color.White, modifier = Modifier.height(14.dp))
                Text(
                    text = "Speech Synth",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.5.sp
                )
            }

            // Voice → Text (regional languages)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(ClayShapeLarge)
                    .background(Color(0x33000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Crossfade(targetState = idx, label = "greeting") { i ->
                        Column(verticalArrangement = Arrangement.Center) {
                            Text(
                                text = greetings[i].first,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                maxLines = 1
                            )
                            Text(
                                text = "Text · ${greetings[i].second}",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Regional Indian Languages Cloud (Dynamic highlighting)
            val displayLangs = listOf(
                "हिन्दी" to "Hindi",
                "বাংলা" to "Bengali",
                "தமிழ்" to "Tamil",
                "తెలుగు" to "Telugu",
                "ಕನ್ನಡ" to "Kannada",
                "മലയാളം" to "Malayalam",
                "मराठी" to "Marathi",
                "ગુજરાતી" to "Gujarati",
                "ਪੰਜਾਬੀ" to "Punjabi"
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val currentLang = greetings[idx].second
                for ((nativeName, engName) in displayLangs) {
                    val isCurrent = engName == currentLang
                    val borderCol = if (isCurrent) Color.White else Color.White.copy(alpha = 0.25f)
                    val bgCol = if (isCurrent) Color.White.copy(alpha = 0.20f) else CardBg.copy(alpha = 0.15f)
                    val textCol = if (isCurrent) Color.White else Color.White.copy(alpha = 0.7f)
                    val textWeight = if (isCurrent) FontWeight.Black else FontWeight.Bold
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(bgCol)
                            .border(1.dp, borderCol, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = nativeName,
                            fontSize = 9.sp,
                            fontWeight = textWeight,
                            color = textCol
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StageLabel(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        color = color,
        letterSpacing = 1.5.sp
    )
}

// Pivot of the waving arm, as a fraction of the avatar image (0,0 = top-left).
// Tune these to your art so the arm rotates naturally around the shoulder.
private const val AVATAR_SHOULDER_X = 0.60f
private const val AVATAR_SHOULDER_Y = 0.44f

/**
 * The 3D character shown waving on the home screen.
 *
 * Cut-out animation: when both `avatar_body` (arm removed) and `avatar_arm`
 * (the forearm + hand only, same canvas size) drawables are present, the arm
 * layer is rotated around the shoulder so the hand genuinely swings over the
 * realistic body image. If only the single `avatar_wave` image exists, it
 * falls back to an active waving motion. Signal rings radiate from the hand.
 */
@Composable
private fun AvatarWave(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Dynamically look up the raw video resource 'avatar' (.mp4, .webm, etc.)
    val videoId = remember(context) {
        context.resources.getIdentifier("avatar", "raw", context.packageName)
    }
    val hasVideo = videoId != 0

    // Optional layered assets are looked up by name so the project still builds
    // before you add them (no hard R.drawable reference required).
    val armId = remember(context) {
        context.resources.getIdentifier("avatar_arm", "drawable", context.packageName)
    }
    val bodyId = remember(context) {
        context.resources.getIdentifier("avatar_body", "drawable", context.packageName)
    }
    val hasLayers = armId != 0 && bodyId != 0

    val transition = rememberInfiniteTransition(label = "avatar")
    // Real arm swing for the layered version: a wider, quicker wave.
    val swing by transition.animateFloat(
        initialValue = -11f,
        targetValue = 13f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "swing"
    )
    // Faster, wider waving rotation for the single-image avatar.
    val waveRotation by transition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveRotation"
    )
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring"
    )
    val cyan = ElectricCyan

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Signal rings broadcasting from the raised hand. Skipped for the video,
        // which fills the frame and would hide them anyway.
        if (!hasVideo) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val emit = Offset(size.width * 0.78f, size.height * 0.32f)
                for (i in 0 until 3) {
                    val pr = ((ring + i / 3f) % 1f)
                    drawCircle(
                        color = cyan.copy(alpha = (1f - pr) * 0.32f),
                        radius = pr * size.width * 0.42f,
                        center = emit,
                        style = Stroke(width = 3f)
                    )
                }
            }
        }

        if (hasVideo) {
            // Looping, silent, center-cropped avatar video that fills the frame.
            AvatarVideo(videoId = videoId, modifier = Modifier.fillMaxSize())
        } else if (hasLayers) {
            // Body (arm removed) sits still; the arm layer swings at the shoulder.
            Image(
                painter = painterResource(id = bodyId),
                contentDescription = "Person wearing the sensor glove",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Image(
                painter = painterResource(id = armId),
                contentDescription = "Waving hand",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = swing
                        transformOrigin = TransformOrigin(AVATAR_SHOULDER_X, AVATAR_SHOULDER_Y)
                    }
            )
        } else {
            // Waving single-image avatar — pivoting from the bottom torso area.
            Image(
                painter = painterResource(id = R.drawable.avatar_wave),
                contentDescription = "Person waving while wearing the sensor glove",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = waveRotation
                        transformOrigin = TransformOrigin(0.5f, 0.85f)
                    }
            )
        }
    }
}

/**
 * Plays a looping, silent avatar video (res/raw/avatar) center-cropped to
 * completely fill its container — no letterboxing/black bars. A TextureView
 * is used (not VideoView/SurfaceView) so Compose can clip the rounded corners,
 * and the MediaPlayer is released when the view leaves the composition.
 */
@Composable
private fun AvatarVideo(videoId: Int, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val texture = android.view.TextureView(ctx)
            val player = android.media.MediaPlayer().apply {
                isLooping = true
                setVolume(0f, 0f)
            }
            texture.tag = player // retrieved in onRelease for cleanup

            var videoW = 0
            var videoH = 0
            var started = false

            fun applyCenterCrop() {
                val w = texture.width.toFloat()
                val h = texture.height.toFloat()
                if (w <= 0f || h <= 0f || videoW == 0 || videoH == 0) return
                // Scale so the video covers the view, then center it.
                val scale = maxOf(w / videoW, h / videoH)
                val matrix = android.graphics.Matrix()
                matrix.setScale(
                    (videoW * scale) / w,
                    (videoH * scale) / h,
                    w / 2f,
                    h / 2f
                )
                texture.setTransform(matrix)
            }

            player.setOnVideoSizeChangedListener { _, width, height ->
                videoW = width
                videoH = height
                applyCenterCrop()
            }
            player.setOnPreparedListener { it.start() }

            texture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    try {
                        player.setSurface(android.view.Surface(st))
                        if (!started) {
                            started = true
                            val uri = android.net.Uri.parse("android.resource://${ctx.packageName}/$videoId")
                            player.setDataSource(ctx, uri)
                            player.prepareAsync()
                        } else {
                            try { player.start() } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AvatarVideo", "Failed to start avatar video", e)
                    }
                }

                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    applyCenterCrop()
                }

                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            }
            texture
        },
        onRelease = { view ->
            (view.tag as? android.media.MediaPlayer)?.let { mp ->
                try { mp.stop() } catch (_: Exception) {}
                mp.release()
            }
        }
    )
}

/** Small horizontal stream of dots, animating the signal flow. */
@Composable
private fun FlowDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "flow")
    val p by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow"
    )
    val cyan = ElectricCyan
    Canvas(modifier = modifier.height(24.dp)) {
        val w = size.width
        val cy = size.height / 2f
        val n = 3
        for (i in 0 until n) {
            val frac = ((p + i.toFloat() / n) % 1f)
            val fade = (1f - abs(frac - 0.5f) * 2f).coerceIn(0f, 1f)
            drawCircle(
                color = cyan.copy(alpha = fade),
                radius = 4f,
                center = Offset(frac * w, cy)
            )
        }
    }
}

/** Bouncing bars representing the synthesised voice output. */
@Composable
private fun VoiceWave(modifier: Modifier = Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "wave")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(6) { i ->
            val barHeight by transition.animateFloat(
                initialValue = 5f,
                targetValue = 20f,
                animationSpec = infiniteRepeatable(
                    animation = tween(420 + i * 90, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun HeaderActionButton(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clayCard(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun DeviceStatusCard(
    connectionState: BleConnectionState,
    deviceName: String?,
    packetRate: Int,
    batteryPercent: Int,
    onAction: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (connectionState) {
            BleConnectionState.CONNECTED -> AuroraGreen
            BleConnectionState.CONNECTING, BleConnectionState.SCANNING -> ElectricCyan
            BleConnectionState.DISCONNECTED -> SlateText
        },
        label = "statusColor"
    )

    val statusTitle = when (connectionState) {
        BleConnectionState.CONNECTED -> deviceName ?: "Samvaad Glove"
        BleConnectionState.CONNECTING -> "Connecting…"
        BleConnectionState.SCANNING -> "Scanning for glove…"
        BleConnectionState.DISCONNECTED -> "No glove connected"
    }
    val statusSubtitle = when (connectionState) {
        BleConnectionState.CONNECTED -> "Streaming at $packetRate Hz"
        BleConnectionState.CONNECTING -> "Establishing BLE link"
        BleConnectionState.SCANNING -> "Searching nearby devices"
        BleConnectionState.DISCONNECTED -> "Tap connect to pair your glove"
    }

    val isActive = connectionState != BleConnectionState.DISCONNECTED
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ringScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeXL)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            ElectricCyan.copy(alpha = 0.12f),
                            SkyBlue.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Pulsing status icon
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .scale(ringScale)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.14f))
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clayShadow(CircleShape, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                            .clip(CircleShape)
                            .background(CardBg)
                            .border(BorderStroke(2.dp, statusColor.copy(alpha = 0.6f)), CircleShape)
                    ) {
                        Icon(
                            imageVector = when (connectionState) {
                                BleConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
                                BleConnectionState.CONNECTING,
                                BleConnectionState.SCANNING -> Icons.AutoMirrored.Filled.BluetoothSearching
                                BleConnectionState.DISCONNECTED -> Icons.Default.Bluetooth
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = DarkSlateText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (connectionState == BleConnectionState.CONNECTED) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CountBadge(label = "● LIVE", color = AuroraGreen)
                            if (batteryPercent in 0..100) {
                                CountBadge(
                                    label = "🔋 $batteryPercent%",
                                    color = if (batteryPercent <= 20) CrimsonRed else SkyBlue
                                )
                            }
                            CountBadge(label = "$packetRate Hz", color = ElectricCyan)
                        }
                    }
                }

                // Connect / disconnect action
                val actionColor =
                    if (connectionState == BleConnectionState.CONNECTED) CrimsonRed else ElectricCyan
                val actionLabel =
                    if (connectionState == BleConnectionState.CONNECTED) "Disconnect" else "Connect"
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clayShadow(ClayShapeChip, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                        .clip(ClayShapeChip)
                        .background(actionColor)
                        .clickable(onClick = onAction)
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = actionLabel,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = SlateText,
            letterSpacing = 2.sp
        )
        Spacer(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(CardBorder.copy(alpha = 0.6f))
        )
    }
}

@Composable
private fun GradientIconTile(
    icon: ImageVector,
    gradient: List<Color>
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clayShadow(ClayShapeSmall, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
            .clip(ClayShapeSmall)
            .background(Brush.linearGradient(gradient))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
internal fun FeatureCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    gradient: List<Color>,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(168.dp)
            .clayCard(ClayShapeLarge)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                GradientIconTile(icon = icon, gradient = gradient)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = SlateText.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = DarkSlateText
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = SlateText
                )
            }
        }
    }
}

@Composable
internal fun CountBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
internal fun FileColumn(
    modifier: Modifier = Modifier,
    title: String,
    titleColor: Color,
    files: List<String>,
    stripExtension: String,
    emptyMessage: String,
    onDelete: (String) -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = titleColor
        )
        if (files.isEmpty()) {
            Text(
                text = emptyMessage,
                fontSize = 11.sp,
                color = SlateText,
                fontStyle = FontStyle.Italic
            )
        } else {
            files.forEach { fileName ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SubtleBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = titleColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = fileName.removeSuffix(stripExtension),
                        fontSize = 11.sp,
                        color = DarkSlateText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onDelete(fileName) },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete $fileName",
                            tint = CrimsonRed.copy(alpha = 0.8f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun getCsvFiles(context: android.content.Context): List<String> {
    val dir = context.getExternalFilesDir("TelemetryData") ?: return emptyList()
    val files = dir.listFiles { file -> file.isFile && file.extension.lowercase() == "csv" }
    return files?.map { it.name }?.sorted() ?: emptyList()
}

internal fun getModelFiles(context: android.content.Context): List<String> {
    val dir = context.getExternalFilesDir("models") ?: return emptyList()
    val files = dir.listFiles { file -> file.isFile && file.extension.lowercase() == "json" }
    return files?.map { it.name }?.sorted() ?: emptyList()
}

internal fun deleteSpecificCsv(context: android.content.Context, fileName: String): Boolean {
    val dir = context.getExternalFilesDir("TelemetryData") ?: return false
    val file = java.io.File(dir, fileName)
    return file.exists() && file.delete()
}

internal fun deleteSpecificModel(context: android.content.Context, fileName: String): Boolean {
    val dir = context.getExternalFilesDir("models") ?: return false
    val file = java.io.File(dir, fileName)
    return file.exists() && file.delete()
}
