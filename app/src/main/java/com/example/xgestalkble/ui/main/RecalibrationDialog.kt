package com.example.xgestalkble.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.xgestalkble.theme.AmberCalibrate
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.SlateText
import kotlinx.coroutines.delay

// Timings mirror the firmware finger-calibration routine (runFingerCalibration):
//   settle 2 s (hold flat) -> 5 s open (LED slow blink 500 ms) -> 5 s fist (fast 100 ms).
private const val SETTLE_MS = 2_000L
private const val OPEN_MS = 5_000L
private const val FIST_MS = 5_000L
private const val OPEN_BLINK_MS = 500
private const val FIST_BLINK_MS = 100

private enum class CalPhase { READY, SETTLE, OPEN, FIST, DONE, ERROR }

/**
 * Guided recalibration dialog. Sends the recalibrate command to the glove, then
 * runs a local timed coach matching the firmware sequence — the glove stops
 * streaming while it calibrates, so we cannot read live progress and instead
 * reproduce its exact timing and LED-blink cadence on screen.
 *
 * @param connected whether a glove is currently connected (gates the Start button)
 * @param onStart   sends the recalibrate command; returns true if it was dispatched
 */
@Composable
fun RecalibrationDialog(
    connected: Boolean,
    onStart: () -> Boolean,
    onDismiss: () -> Unit
) {
    var phase by remember { mutableStateOf(CalPhase.READY) }
    var remainingSec by remember { mutableIntStateOf(0) }

    // Drive the phase state machine once we leave READY.
    LaunchedEffect(phase) {
        when (phase) {
            CalPhase.SETTLE -> { countdown(SETTLE_MS) { remainingSec = it }; phase = CalPhase.OPEN }
            CalPhase.OPEN   -> { countdown(OPEN_MS)   { remainingSec = it }; phase = CalPhase.FIST }
            CalPhase.FIST   -> { countdown(FIST_MS)   { remainingSec = it }; phase = CalPhase.DONE }
            else -> {}
        }
    }

    val running = phase == CalPhase.SETTLE || phase == CalPhase.OPEN || phase == CalPhase.FIST

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        icon = {
            Icon(
                imageVector = when (phase) {
                    CalPhase.DONE -> Icons.Default.CheckCircle
                    CalPhase.ERROR -> Icons.Default.ErrorOutline
                    CalPhase.FIST -> Icons.Default.BackHand
                    else -> Icons.Default.PanTool
                },
                contentDescription = null,
                tint = when (phase) {
                    CalPhase.DONE -> AuroraGreen
                    CalPhase.ERROR -> CrimsonRed
                    else -> AmberCalibrate
                }
            )
        },
        title = {
            Text(
                text = when (phase) {
                    CalPhase.READY -> "Recalibrate glove"
                    CalPhase.SETTLE -> "Hold still…"
                    CalPhase.OPEN -> "Open your hand"
                    CalPhase.FIST -> "Make a fist"
                    CalPhase.DONE -> "Calibration complete"
                    CalPhase.ERROR -> "Couldn't start"
                },
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (phase) {
                    CalPhase.READY -> ReadyBody(connected)
                    CalPhase.SETTLE -> PhaseBody(
                        "Keep the glove flat and steady while it gets ready.",
                        remainingSec, AmberCalibrate, blinkMs = 0
                    )
                    CalPhase.OPEN -> PhaseBody(
                        "Lay your hand FLAT with all fingers spread OPEN. " +
                        "The glove LED blinks slowly now.",
                        remainingSec, ElectricCyan, blinkMs = OPEN_BLINK_MS
                    )
                    CalPhase.FIST -> PhaseBody(
                        "Squeeze into a TIGHT FIST, keeping the wrist flat. " +
                        "The glove LED blinks fast now.",
                        remainingSec, AmberCalibrate, blinkMs = FIST_BLINK_MS
                    )
                    CalPhase.DONE -> Text(
                        "The glove saved your new finger profile. You can close this and keep signing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateText,
                        textAlign = TextAlign.Center
                    )
                    CalPhase.ERROR -> Text(
                        "The glove didn't accept the command. Make sure it's connected and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlateText,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            when (phase) {
                CalPhase.READY -> TextButton(
                    enabled = connected,
                    onClick = {
                        phase = if (onStart()) CalPhase.SETTLE else CalPhase.ERROR
                    }
                ) { Text("START", color = if (connected) AmberCalibrate else SlateText, fontWeight = FontWeight.Bold) }
                CalPhase.DONE, CalPhase.ERROR ->
                    TextButton(onClick = onDismiss) { Text("DONE", color = AuroraGreen, fontWeight = FontWeight.Bold) }
                else -> Text(
                    "Calibrating…",
                    color = SlateText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        },
        dismissButton = {
            if (!running && phase == CalPhase.READY) {
                TextButton(onClick = onDismiss) { Text("CANCEL") }
            }
        },
        containerColor = CardBg,
        titleContentColor = DarkSlateText,
        textContentColor = SlateText
    )
}

@Composable
private fun ReadyBody(connected: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "This teaches the glove the range of your fingers. It takes about 12 seconds:",
            style = MaterialTheme.typography.bodyMedium,
            color = SlateText,
            textAlign = TextAlign.Center
        )
        StepLine("1.", "Hold flat & still (2s)")
        StepLine("2.", "Open hand, fingers spread (5s)")
        StepLine("3.", "Tight fist, wrist flat (5s)")
        if (!connected) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect the glove first to start.",
                style = MaterialTheme.typography.bodySmall,
                color = CrimsonRed,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StepLine(num: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(num, fontWeight = FontWeight.Bold, color = AmberCalibrate)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = DarkSlateText)
    }
}

@Composable
private fun PhaseBody(instruction: String, remainingSec: Int, accent: androidx.compose.ui.graphics.Color, blinkMs: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        BlinkDot(accent = accent, blinkMs = blinkMs, label = remainingSec.toString())
        Text(
            instruction,
            style = MaterialTheme.typography.bodyMedium,
            color = SlateText,
            textAlign = TextAlign.Center
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
            color = accent,
            trackColor = accent.copy(alpha = 0.15f)
        )
    }
}

/** A pulsing dot whose blink period matches the glove LED for the current step. */
@Composable
private fun BlinkDot(accent: androidx.compose.ui.graphics.Color, blinkMs: Int, label: String) {
    var on by remember { mutableStateOf(true) }
    if (blinkMs > 0) {
        LaunchedEffect(blinkMs) {
            while (true) { on = !on; delay(blinkMs.toLong()) }
        }
    }
    val targetAlpha = if (blinkMs == 0 || on) 1f else 0.25f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = (blinkMs.coerceAtLeast(80) / 2), easing = LinearEasing),
        label = "blink"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(76.dp)
            .border(BorderStroke(2.dp, accent.copy(alpha = 0.4f)), RoundedCornerShape(50))
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.85f))
        )
        Text(label, fontWeight = FontWeight.Black, color = DarkSlateText)
    }
}

/** Counts down [durationMs], reporting whole seconds remaining (ceil) via [onTick]. */
private suspend fun countdown(durationMs: Long, onTick: (Int) -> Unit) {
    val stepMs = 100L
    var elapsed = 0L
    onTick(((durationMs + 999) / 1000).toInt())
    while (elapsed < durationMs) {
        delay(stepMs)
        elapsed += stepMs
        val remaining = ((durationMs - elapsed + 999) / 1000).toInt().coerceAtLeast(0)
        onTick(remaining)
    }
}
