package com.example.xgestalkble.ui.monitor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.xgestalkble.ble.BleConnectionState
import com.example.xgestalkble.ble.GloveDataPacket
import com.example.xgestalkble.theme.AmberCalibrate
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.MidnightBg
import com.example.xgestalkble.theme.ObsidianBg
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.ClayShapeMedium
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.ClayShadow
import com.example.xgestalkble.theme.ClayHighlight
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayShadow
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.ui.main.MainScreenViewModel
import kotlin.math.absoluteValue

@Composable
fun SerialMonitorScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(context.applicationContext) }
    val connectionState by viewModel.connectionState.collectAsState()
    val dataPacket by viewModel.dataPacket.collectAsState()
    val packetRate by viewModel.packetRate.collectAsState()
    val logs by viewModel.logs.collectAsState()

    var isPaused by remember { mutableStateOf(false) }
    var frozenPacket by remember { mutableStateOf<GloveDataPacket?>(null) }

    // Freeze telemetry when paused
    LaunchedEffect(dataPacket) {
        if (!isPaused && dataPacket != null) {
            frozenPacket = dataPacket
        }
    }

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
            // Action Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = DarkSlateText
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "SERIAL MONITOR",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = DarkSlateText
                        )
                        Text(
                            text = "Telemetry frequency: ${packetRate} Hz",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText
                        )
                    }
                }

                // Control Actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // READY status chip (firmware bit 0x80)
                    val ready = frozenPacket?.isReady == true
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CardBg)
                            .border(
                                BorderStroke(1.dp, if (ready) AuroraGreen else AmberCalibrate),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (ready) "READY" else "SETTLING",
                            color = if (ready) AuroraGreen else AmberCalibrate,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { isPaused = !isPaused }
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = "Pause Toggle",
                            tint = if (isPaused) AuroraGreen else AmberCalibrate
                        )
                    }
                    IconButton(
                        onClick = { viewModel.clearLogs() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Logs",
                            tint = CrimsonRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Grid of 6 MPU6050 Sensors
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val packet = frozenPacket
                items(6) { index ->
                    SensorCard(sensorIndex = index, packet = packet)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // CRT Terminal Log Feed
            Text(
                text = "LOG CONSOLE FEED",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clayShadow(ClayShapeSmall, ClayShadow, ClayHighlight)
                    .clip(ClayShapeSmall)
                    .background(Color(0xFF020205))
            ) {
                val listState = rememberLazyListState()

                // Auto Scroll Terminal to bottom
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) {
                        listState.animateScrollToItem(logs.size - 1)
                    }
                }

                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No terminal logs available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateText,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        items(logs) { log ->
                            Text(
                                text = log,
                                color = AuroraGreen,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                ),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SensorCard(
    sensorIndex: Int,
    packet: GloveDataPacket?
) {
    // Display order: 0=wrist, 1..5 = thumb..little (matches firmware finger order)
    val sensorName = when (sensorIndex) {
        0 -> "WRIST SENSOR"
        1 -> "THUMB"
        2 -> "INDEX FINGER"
        3 -> "MIDDLE FINGER"
        4 -> "RING FINGER"
        5 -> "LITTLE FINGER"
        else -> "SENSOR #$sensorIndex"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeMedium)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            // Card Title
            Text(
                text = sensorName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (sensorIndex == 0) {
                // Wrist: quantized accel levels (-2..+2) + integer gyro dps
                Text(
                    text = "ACCEL LEVELS + GYRO",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = SlateText,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val acc = packet?.wristAccel ?: IntArray(3)
                val gyro = packet?.wristGyro ?: IntArray(3)
                LevelRow("ACC X", acc.getOrElse(0) { 0 }, ElectricCyan)
                LevelRow("ACC Y", acc.getOrElse(1) { 0 }, ElectricCyan)
                LevelRow("ACC Z", acc.getOrElse(2) { 0 }, ElectricCyan)
                GyroRow("GYRO X", gyro.getOrElse(0) { 0 })
                GyroRow("GYRO Y", gyro.getOrElse(1) { 0 })
                GyroRow("GYRO Z", gyro.getOrElse(2) { 0 })
            } else {
                // Fingers: three quantized accel levels (-2..+2)
                val fi = sensorIndex - 1
                Text(
                    text = "ACCEL LEVELS",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = SlateText,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val axes = packet?.fingerAccel?.getOrNull(fi) ?: IntArray(3)
                LevelRow("ACC X", axes.getOrElse(0) { 0 }, AuroraGreen)
                LevelRow("ACC Y", axes.getOrElse(1) { 0 }, AuroraGreen)
                LevelRow("ACC Z", axes.getOrElse(2) { 0 }, AuroraGreen)
            }
        }
    }
}

// Quantized accel level row: value in [-2..+2], bar centred at zero.
@Composable
fun LevelRow(label: String, level: Int, color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = SlateText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (level > 0) "+$level" else "$level",
                color = DarkSlateText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        val fraction = (level.absoluteValue.toFloat() / 2f).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(SlateText.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(if (level < 0) AmberCalibrate else color)
            )
        }
    }
}

// Integer gyro deg/s row (bar scaled to ±500 dps).
@Composable
fun GyroRow(label: String, dps: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = SlateText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$dps°/s",
                color = DarkSlateText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        val fraction = (dps.absoluteValue.toFloat() / 500f).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(SlateText.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(ElectricCyan)
            )
        }
    }
}
