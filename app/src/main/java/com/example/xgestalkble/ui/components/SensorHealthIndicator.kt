package com.example.xgestalkble.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xgestalkble.theme.AmberCalibrate
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.ui.main.MainScreenViewModel

// Per-sensor health: bit i of sensorStatus = mux channel i (firmware
// computeSensorStatus + SENSOR_CH_MAP map). bit7 (0x80) = READY frame.
private val SENSOR_HEALTH_BITS = listOf(
    "Thumb" to 3, "Index" to 1, "Middle" to 0, "Ring" to 4, "Little" to 2, "Wrist" to 5
)

/**
 * Live per-sensor health row: one dot per IMU (green = reporting, red =
 * silent/faulty) plus a READY badge. Reads the BLE packet internally so only
 * this row recomposes at the ~50 Hz stream rate. Shared by the recording,
 * live-prediction and conversation screens.
 */
@Composable
fun SensorHealthIndicator(
    viewModel: MainScreenViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val packet by viewModel.dataPacket.collectAsState()
    val status = packet?.sensorStatus ?: 0
    val ready = packet?.isReady ?: false
    val healthyCount = SENSOR_HEALTH_BITS.count { packet != null && (status and (1 shl it.second)) != 0 }

    if (compact) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "SENSORS ($healthyCount/6):",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SlateText.copy(alpha = 0.8f),
                    letterSpacing = 1.sp
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SENSOR_HEALTH_BITS.forEach { (_, bit) ->
                        val healthy = packet != null && (status and (1 shl bit)) != 0
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (healthy) AuroraGreen else CrimsonRed)
                                .border(BorderStroke(0.5.dp, (if (healthy) AuroraGreen else CrimsonRed).copy(alpha = 0.3f)), CircleShape)
                        )
                    }
                }
            }
            Text(
                text = if (ready) "READY" else "SETTLING",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = if (ready) AuroraGreen else AmberCalibrate,
                fontSize = 9.sp
            )
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SENSOR HEALTH  ($healthyCount/6)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SlateText,
                    letterSpacing = 1.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background((if (ready) AuroraGreen else AmberCalibrate).copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (ready) "READY" else "SETTLING",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (ready) AuroraGreen else AmberCalibrate
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SENSOR_HEALTH_BITS.forEach { (name, bit) ->
                    val healthy = packet != null && (status and (1 shl bit)) != 0
                    SensorDot(name, healthy)
                }
            }
        }
    }
}

@Composable
private fun SensorDot(name: String, healthy: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (healthy) AuroraGreen else CrimsonRed)
                .border(
                    BorderStroke(1.dp, (if (healthy) AuroraGreen else CrimsonRed).copy(alpha = 0.4f)),
                    CircleShape
                )
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = SlateText
        )
    }
}
