package com.example.xgestalkble.ui.sound

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.xgestalkble.theme.SubtleBg
import com.example.xgestalkble.theme.VioletAccent
import com.example.xgestalkble.theme.ClayShapeLarge
import com.example.xgestalkble.theme.ClayShapeMedium
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.ClayShadow
import com.example.xgestalkble.theme.ClayHighlight
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayInset
import com.example.xgestalkble.theme.clayShadow
import com.example.xgestalkble.utils.PermissionUtils
import com.example.xgestalkble.utils.SoundDetector
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One detected sound event for the on-screen history. */
private data class SoundEvent(val label: String, val level: Float, val time: Long)

// ── Alert hardware helpers ──────────────────────────────────────────────────

private fun vibrateAlert(context: Context) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        // Two firm buzzes — distinct from a normal notification.
        val pattern = longArrayOf(0, 250, 120, 250)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    } catch (_: Exception) { /* device without a vibrator */ }
}

private fun cameraIdWithFlash(context: Context): String? = try {
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    cm.cameraIdList.firstOrNull {
        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
} catch (_: Exception) { null }

private suspend fun strobeFlashlight(context: Context) {
    val camId = cameraIdWithFlash(context) ?: return
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
        repeat(6) {
            cm.setTorchMode(camId, true)
            delay(110)
            cm.setTorchMode(camId, false)
            delay(110)
        }
    } catch (_: Exception) {
        runCatching { cm.setTorchMode(camId, false) }
    }
}

@Composable
fun SoundAlertScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var listening by remember { mutableStateOf(false) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    var sensitivity by remember { mutableFloatStateOf(0.55f) } // 0..1, higher = more sensitive
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var vibrateOn by remember { mutableStateOf(true) }
    var flashOn by remember { mutableStateOf(true) }
    var torchOn by remember { mutableStateOf(true) }

    val events = remember { mutableStateListOf<SoundEvent>() }
    var flashPulse by remember { mutableIntStateOf(0) } // bump per event → screen flash
    var screenFlashing by remember { mutableStateOf(false) }

    val detector = remember {
        SoundDetector().apply {
            onLevel = { micLevel = it }
            onError = { errorMsg = it; listening = false }
        }
    }

    // Map sensitivity (0..1, higher=more sensitive) to an RMS threshold (lower=more sensitive).
    LaunchedEffect(sensitivity) {
        detector.threshold = (0.35f - sensitivity * 0.30f).coerceIn(0.04f, 0.40f)
    }

    // Wire the detection callback so it can touch Compose state + fire alerts.
    DisposableEffect(detector) {
        detector.onSound = { level ->
            events.add(0, SoundEvent("Loud sound", level, System.currentTimeMillis()))
            if (events.size > 30) events.removeAt(events.size - 1)
            flashPulse++
            if (vibrateOn) vibrateAlert(context)
        }
        onDispose { detector.stop() }
    }

    // Screen flash + flashlight strobe whenever an event bumps flashPulse.
    LaunchedEffect(flashPulse) {
        if (flashPulse == 0) return@LaunchedEffect
        if (flashOn) {
            repeat(3) {
                screenFlashing = true
                delay(120)
                screenFlashing = false
                delay(120)
            }
        }
        if (torchOn) strobeFlashlight(context)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            errorMsg = null
            detector.start()
            listening = detector.isRunning()
        } else {
            errorMsg = "Microphone permission is required to listen for sounds."
        }
    }

    val toggleListening = {
        if (listening) {
            detector.stop()
            listening = false
            micLevel = 0f
        } else if (PermissionUtils.hasAudioPermission(context)) {
            errorMsg = null
            detector.start()
            listening = detector.isRunning()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Transparent at rest (no tint), a soft amber wash only while flashing.
    val flashColor by animateColorAsState(
        targetValue = if (screenFlashing) AmberCalibrate.copy(alpha = 0.5f) else Color.Transparent,
        label = "screenFlash"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ObsidianBg, MidnightBg)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DarkSlateText)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "SOUND ALERTS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = DarkSlateText,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Your phone listens and alerts you to important sounds",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Big start/stop control + live mic meter
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clayCard(ClayShapeLarge)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val ringColor = if (listening) AuroraGreen else SlateText
                    val meterScale = if (listening) (0.6f + micLevel * 2f).coerceIn(0.6f, 1.0f) else 0.6f
                    val animatedScale by animateFloatAsState(targetValue = meterScale, label = "meter")

                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                        // Pulsing level halo
                        Box(
                            modifier = Modifier
                                .size(150.dp)
                                .clip(CircleShape)
                                .background(ringColor.copy(alpha = 0.10f * animatedScale + 0.04f))
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(110.dp)
                                .clayShadow(CircleShape, ClayShadow, ClayHighlight, offset = 6.dp, blur = 14.dp)
                                .clip(CircleShape)
                                .background(
                                    if (listening) Brush.linearGradient(listOf(AuroraGreen, ElectricCyan))
                                    else Brush.linearGradient(listOf(CardBg, SubtleBg))
                                )
                                .clickable { toggleListening() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = if (listening) "Stop listening" else "Start listening",
                                tint = if (listening) Color.White else SlateText,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (listening) "LISTENING…" else "TAP TO START LISTENING",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp,
                        color = if (listening) AuroraGreen else SlateText
                    )
                    if (listening) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Keep this screen open. You'll feel/see an alert on a loud sound.",
                            fontSize = 11.sp,
                            color = SlateText,
                            textAlign = TextAlign.Center
                        )
                    }

                    errorMsg?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = CrimsonRed, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Alert-method toggles
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clayCard(ClayShapeMedium)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("ALERT ME WITH", fontWeight = FontWeight.Black, fontSize = 11.sp, color = SlateText, letterSpacing = 2.sp)
                    Spacer(Modifier.height(10.dp))
                    AlertToggleRow(Icons.Default.Vibration, "Vibration", vibrateOn) { vibrateOn = it }
                    AlertToggleRow(Icons.Default.NotificationsActive, "Screen flash", flashOn) { flashOn = it }
                    AlertToggleRow(Icons.Default.Bolt, "Flashlight strobe", torchOn) { torchOn = it }

                    Spacer(Modifier.height(14.dp))
                    Text("SENSITIVITY", fontWeight = FontWeight.Black, fontSize = 11.sp, color = SlateText, letterSpacing = 2.sp)
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricCyan,
                            inactiveTrackColor = CardBorder.copy(alpha = 0.3f)
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Only loud sounds", fontSize = 10.sp, color = SlateText)
                        Text("Quiet sounds too", fontSize = 10.sp, color = SlateText)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "RECENT SOUNDS",
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                color = SlateText,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(8.dp))

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clayInset(ClayShapeMedium, SubtleBg)
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (listening) "Listening… detected sounds will appear here."
                        else "Start listening to capture sounds around you.",
                        color = SlateText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // weight(1f) bounds the list to the leftover height so it scrolls.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)
                ) {
                    items(events) { ev -> SoundEventRow(ev) }
                }
            }
        }

        // Full-screen flash overlay — transparent at rest, amber wash on an alert.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(flashColor)
        )
    }
}

@Composable
private fun AlertToggleRow(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (checked) ElectricCyan else SlateText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = DarkSlateText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ElectricCyan,
                uncheckedThumbColor = SlateText,
                uncheckedTrackColor = SubtleBg
            )
        )
    }
}

@Composable
private fun SoundEventRow(ev: SoundEvent) {
    val time = remember(ev.time) { SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(ev.time)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(BorderStroke(1.dp, AmberCalibrate.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AmberCalibrate.copy(alpha = 0.18f))
        ) {
            Icon(Icons.Default.NotificationsActive, null, tint = AmberCalibrate, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(ev.label, color = DarkSlateText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Detected at $time", color = SlateText, fontSize = 11.sp)
        }
        // Simple loudness pips.
        val pips = (ev.level * 5f).toInt().coerceIn(1, 5)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(5) { i ->
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = (8 + i * 3).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < pips) AmberCalibrate else CardBorder.copy(alpha = 0.3f))
                )
            }
        }
    }
}
