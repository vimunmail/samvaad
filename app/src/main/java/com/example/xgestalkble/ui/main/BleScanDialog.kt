package com.example.xgestalkble.ui.main

import com.example.xgestalkble.ble.gloveDisplayName

import android.bluetooth.le.ScanResult
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.MidnightBg
import com.example.xgestalkble.theme.ObsidianBg
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.ClayShapeXL
import com.example.xgestalkble.theme.ClayShapeMedium
import com.example.xgestalkble.theme.ClayShadow
import com.example.xgestalkble.theme.ClayHighlight
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayShadow

@Composable
fun BleScanDialog(
    scanResults: List<ScanResult>,
    onDeviceSelected: (ScanResult) -> Unit,
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clayShadow(ClayShapeXL, ClayShadow, ClayHighlight)
                .clip(ClayShapeXL)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(MidnightBg, ObsidianBg)
                    )
                )
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Pulsing title
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.96f,
                    targetValue = 1.04f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                )

                Text(
                    text = "DISCOVERING SAMVAAD GLOVE",
                    style = MaterialTheme.typography.titleMedium,
                    color = ElectricCyan,
                    modifier = Modifier.scale(scale)
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Make sure your glove is powered on and advertising",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(CardBorder.copy(alpha = 0.2f))
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (scanResults.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = ElectricCyan)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Searching for nearby devices...", color = SlateText)
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(scanResults) { result ->
                            DeviceItemCard(result = result, onClick = { onDeviceSelected(result) })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismissRequest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardBg,
                        contentColor = DarkSlateText
                    ),
                    border = BorderStroke(1.dp, CardBorder.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("CANCEL SCAN")
                }
            }
        }
    }
}

@Composable
fun DeviceItemCard(result: ScanResult, onClick: () -> Unit) {
    val device = result.device
    // Handle permissions check safely for device name
    val deviceName = try {
        device.name ?: "Unknown Device"
    } catch (e: SecurityException) {
        "Glove Hardware"
    }
    val isGlove = deviceName.contains("Gestalk_Glove", ignoreCase = true) || deviceName.contains("Glove", ignoreCase = true)

    val cardBg = CardBg

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeMedium, cardBg)
            .then(
                if (isGlove) Modifier.border(BorderStroke(2.dp, AuroraGreen), ClayShapeMedium)
                else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = "Bluetooth",
                    tint = if (isGlove) AuroraGreen else ElectricCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = gloveDisplayName(deviceName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = DarkSlateText
                    )
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                    if (isGlove) {
                        Text(
                            text = "COMPATIBLE HARDWARE",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuroraGreen
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${result.rssi} dBm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        result.rssi >= -60 -> AuroraGreen
                        result.rssi >= -80 -> ElectricCyan
                        else -> CrimsonRed
                    }
                )
            }
        }
    }
}
