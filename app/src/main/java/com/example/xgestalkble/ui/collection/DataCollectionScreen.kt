package com.example.xgestalkble.ui.collection

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.xgestalkble.ble.BleConnectionState
import com.example.xgestalkble.ml.TrainingManager
import com.example.xgestalkble.theme.AmberCalibrate
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.MidnightBg
import com.example.xgestalkble.theme.ObsidianBg
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.SubtleBg
import com.example.xgestalkble.theme.ClayShapeLarge
import com.example.xgestalkble.theme.ClayShapeMedium
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.ClayShadow
import com.example.xgestalkble.theme.ClayHighlight
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayInset
import com.example.xgestalkble.theme.clayShadow
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.ui.components.SensorHealthIndicator
import com.example.xgestalkble.ui.main.MainScreenViewModel
import com.example.xgestalkble.utils.RecordingModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun analyzeCsvDataset(context: android.content.Context, fileName: String): Map<String, Int> {
    val dir = context.getExternalFilesDir("TelemetryData") ?: return emptyMap()
    // Support both with and without .csv extension
    val csvName = if (fileName.endsWith(".csv")) fileName else "${fileName}.csv"
    val file = File(dir, csvName)
    if (!file.exists() || !file.isFile) return emptyMap()

    val labelCounts = mutableMapOf<String, Int>()
    try {
        file.bufferedReader().use { reader ->
            val headerLine = reader.readLine() ?: return emptyMap()
            // In webapp format, Label is always column 0
            var line = reader.readLine()
            while (line != null) {
                val cols = line.split(",")
                if (cols.isNotEmpty()) {
                    val label = cols[0].trim()
                    if (label.isNotEmpty()) {
                        labelCounts[label] = (labelCounts[label] ?: 0) + 1
                    }
                }
                line = reader.readLine()
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("CsvParser", "Exception reading CSV", e)
    }
    return labelCounts
}

private fun clearAllDatasets(context: android.content.Context) {
    val dir = context.getExternalFilesDir("TelemetryData") ?: return
    val files = dir.listFiles { file -> file.isFile && file.extension.lowercase() == "csv" }
    files?.forEach { 
        val deleted = it.delete()
        android.util.Log.d("CsvParser", "Deleted file: ${it.name} -> $deleted")
    }
}

internal fun deleteLabelFromCsv(context: android.content.Context, fileName: String, labelToDelete: String): Boolean {
    val dir = context.getExternalFilesDir("TelemetryData") ?: return false
    val csvName = if (fileName.endsWith(".csv")) fileName else "${fileName}.csv"
    val file = File(dir, csvName)
    if (!file.exists() || !file.isFile) return false

    return try {
        val lines = file.readLines()
        if (lines.isEmpty()) return false
        
        val header = lines.first()
        val remainingLines = lines.drop(1).filter { line ->
            val cols = line.split(",")
            if (cols.isNotEmpty()) {
                val label = cols[0].trim()
                label != labelToDelete
            } else {
                true
            }
        }
        
        file.bufferedWriter().use { writer ->
            writer.write(header)
            writer.newLine()
            for (line in remainingLines) {
                writer.write(line)
                writer.newLine()
            }
        }
        true
    } catch (e: java.lang.Exception) {
        android.util.Log.e("CsvParser", "Exception deleting label $labelToDelete from CSV $fileName", e)
        false
    }
}

@Composable
fun DataCollectionScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(context.applicationContext) }
    val connectionState by viewModel.connectionState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingStatus by viewModel.recordingStatus.collectAsState()
    val recordedCount by viewModel.recordedCount.collectAsState()
    val skippedCount by viewModel.skippedCount.collectAsState()
    val livePacket by viewModel.dataPacket.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    // Tab state (0 for Data Recording, 1 for Model Training)
    var activeTab by remember { mutableStateOf(0) }

    var datasetSummary by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    // Dataset collection state config
    var csvFileName by remember { mutableStateOf("gesture_dataset") }
    var gestureLabel by remember { mutableStateOf("fist") }
    var selectedModelType by remember { mutableStateOf(RecordingModelType.RFC) }

    // Training states — owned by the process-wide TrainingManager so a run
    // survives leaving this screen or closing the app; its foreground service
    // shows the live progress notification. This screen just renders the flows.
    val isTraining by TrainingManager.isTraining.collectAsState()
    var trainingModelType by remember { mutableStateOf(RecordingModelType.RFC) }
    var estimatorsInput by remember { mutableStateOf("200") }
    val trainingProgress by TrainingManager.progress.collectAsState()
    val trainingStatusText by TrainingManager.statusText.collectAsState()
    val trainingLogs by TrainingManager.logs.collectAsState()

    // New selection states for CSV dataset list and custom trained model output name
    var availableCsvFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCsvFile by remember { mutableStateOf("") }
    var outputModelName by remember { mutableStateOf("gesture_model") }
    var showCsvDropdown by remember { mutableStateOf(false) }
    
    var selectedCsvSummary by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var showDeleteConfirmDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // Pair(label, csvFileName)

    // Dropdown state for picking a pre-recorded CSV in the Data Recording tab
    var showRecordCsvDropdown by remember { mutableStateOf(false) }

    // Summary of previously recorded/trained labels in the active CSV dataset
    var recordCsvSummary by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var showExistingLabelDropdown by remember { mutableStateOf(false) }

    // Auto-analyze currently loaded CSV file in the Recording tab
    LaunchedEffect(csvFileName, activeTab) {
        if (csvFileName.isNotBlank()) {
            recordCsvSummary = analyzeCsvDataset(context, csvFileName)
        } else {
            recordCsvSummary = emptyMap()
        }
    }

    // Query available CSV files whenever the user switches tabs. Both the
    // recording tab (to append new labels into an existing dataset) and the
    // training tab need the up-to-date list.
    LaunchedEffect(activeTab) {
        val dir = context.getExternalFilesDir("TelemetryData")
        val files = dir?.listFiles { file -> file.isFile && file.extension.lowercase() == "csv" }
            ?.map { it.name }
            ?.sorted() ?: emptyList()
        availableCsvFiles = files
        // On the training tab, default-select the first file found if none chosen
        if (activeTab == 1 && selectedCsvFile.isEmpty() && files.isNotEmpty()) {
            selectedCsvFile = files[0]
        }
    }

    // Auto-analyze selected CSV file summary in training tab
    LaunchedEffect(selectedCsvFile, activeTab) {
        if (activeTab == 1 && selectedCsvFile.isNotEmpty()) {
            selectedCsvSummary = analyzeCsvDataset(context, selectedCsvFile)
        }
    }

    LaunchedEffect(trainingModelType) {
        // Accuracy-first defaults. RFC accuracy rises with more trees (it can't
        // overfit from tree count); TCN keeps the best-val checkpoint so extra
        // epochs only help. Lower these if on-device training is too slow.
        estimatorsInput = if (trainingModelType == RecordingModelType.RFC) "200" else "60"
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Training runs in the background service now, so only an active
                // recording pins the user to this screen.
                IconButton(onClick = onBackClick, enabled = !isRecording) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (isRecording) SlateText else DarkSlateText
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "DATA & TRAINING HUB",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DarkSlateText
                    )
                    Text(
                        text = "Manage gesture datasets and train classifier models",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateText
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Professional Custom Tab Selector Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clayInset(ClayShapeSmall, CardBg)
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Tab 0: Data Recording
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeTab == 0) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent)
                        .border(
                            BorderStroke(1.dp, if (activeTab == 0) ElectricCyan.copy(alpha = 0.4f) else Color.Transparent),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = !isRecording) { activeTab = 0 }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = "DATA RECORDING",
                        color = if (activeTab == 0) ElectricCyan else SlateText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Tab 1: Model Training
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeTab == 1) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent)
                        .border(
                            BorderStroke(1.dp, if (activeTab == 1) ElectricCyan.copy(alpha = 0.4f) else Color.Transparent),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = !isRecording) { activeTab = 1 }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = "MODEL TRAINING",
                        color = if (activeTab == 1) ElectricCyan else SlateText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Active Panel View
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                if (activeTab == 0) {
                    // TAB 0: DATASET COLLECTOR
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clayCard(ClayShapeLarge)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "GESTURE DATASET CREATOR",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Collect quantized sensor data from connected glove. CSV format: Label + 21 features (matching webapp). Only READY frames are saved.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateText,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            
                            Spacer(modifier = Modifier.height(20.dp))

                            OutlinedTextField(
                                value = csvFileName,
                                onValueChange = { csvFileName = it },
                                label = { Text("CSV FILE NAME") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isRecording,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricCyan,
                                    unfocusedBorderColor = CardBorder.copy(alpha = 0.3f),
                                    focusedLabelColor = ElectricCyan,
                                    unfocusedLabelColor = SlateText,
                                    focusedTextColor = DarkSlateText,
                                    unfocusedTextColor = DarkSlateText
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Pick a pre-recorded dataset to append into. Recording
                            // appends (header is only written for a brand-new file),
                            // so selecting an existing CSV lets you add new labels to it.
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !isRecording) {
                                            showRecordCsvDropdown = !showRecordCsvDropdown
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Load existing dataset",
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Load an existing dataset to add new labels",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricCyan
                                    )
                                }
                                DropdownMenu(
                                    expanded = showRecordCsvDropdown,
                                    onDismissRequest = { showRecordCsvDropdown = false },
                                    modifier = Modifier
                                        .fillMaxWidth(0.9f)
                                        .background(MidnightBg)
                                        .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(8.dp))
                                ) {
                                    if (availableCsvFiles.isEmpty()) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "No saved datasets yet — type a name above to start a new one",
                                                    color = SlateText
                                                )
                                            },
                                            onClick = { showRecordCsvDropdown = false }
                                        )
                                    } else {
                                        availableCsvFiles.forEach { fileName ->
                                            DropdownMenuItem(
                                                text = { Text(fileName, color = DarkSlateText) },
                                                onClick = {
                                                    // DataCollector adds the ".csv" suffix itself.
                                                    csvFileName = fileName.removeSuffix(".csv")
                                                    showRecordCsvDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                             OutlinedTextField(
                                 value = gestureLabel,
                                 onValueChange = { gestureLabel = it },
                                 label = { Text("GESTURE LABEL NAME") },
                                 supportingText = { Text("Write the full English meaning, for example: I do not need help. New models can generate regional translations automatically.") },
                                 modifier = Modifier.fillMaxWidth(),
                                 singleLine = true,
                                 enabled = !isRecording,
                                 colors = OutlinedTextFieldDefaults.colors(
                                     focusedBorderColor = ElectricCyan,
                                     unfocusedBorderColor = CardBorder.copy(alpha = 0.3f),
                                     focusedLabelColor = ElectricCyan,
                                     unfocusedLabelColor = SlateText,
                                     focusedTextColor = DarkSlateText,
                                     unfocusedTextColor = DarkSlateText
                                 )
                             )

                             // Previously recorded/trained labels selection for the loaded CSV
                             if (recordCsvSummary.isNotEmpty()) {
                                 Spacer(modifier = Modifier.height(8.dp))
                                 Box(modifier = Modifier.fillMaxWidth()) {
                                     Row(
                                         modifier = Modifier
                                             .fillMaxWidth()
                                             .clip(RoundedCornerShape(8.dp))
                                             .clickable(enabled = !isRecording) {
                                                 showExistingLabelDropdown = !showExistingLabelDropdown
                                             }
                                             .background(MidnightBg)
                                             .border(BorderStroke(1.dp, CardBorder.copy(alpha = 0.5f)), RoundedCornerShape(8.dp))
                                             .padding(horizontal = 10.dp, vertical = 8.dp),
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween
                                     ) {
                                         Row(
                                             verticalAlignment = Alignment.CenterVertically,
                                             horizontalArrangement = Arrangement.spacedBy(6.dp)
                                         ) {
                                             Icon(
                                                 imageVector = Icons.Default.ArrowDropDown,
                                                 contentDescription = "Select previously trained label",
                                                 tint = ElectricCyan,
                                                 modifier = Modifier.size(20.dp)
                                             )
                                             Text(
                                                 text = "SELECT PREVIOUS LABEL (${recordCsvSummary.size} in '$csvFileName')",
                                                 style = MaterialTheme.typography.bodySmall,
                                                 fontWeight = FontWeight.Bold,
                                                 color = ElectricCyan
                                             )
                                         }
                                         Text(
                                             text = if (showExistingLabelDropdown) "▼" else "▶",
                                             style = MaterialTheme.typography.bodySmall,
                                             color = SlateText
                                         )
                                     }

                                     DropdownMenu(
                                         expanded = showExistingLabelDropdown,
                                         onDismissRequest = { showExistingLabelDropdown = false },
                                         modifier = Modifier
                                             .fillMaxWidth(0.9f)
                                             .background(MidnightBg)
                                             .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(8.dp))
                                     ) {
                                         recordCsvSummary.forEach { (lbl, count) ->
                                             DropdownMenuItem(
                                                 text = {
                                                     Row(
                                                         modifier = Modifier.fillMaxWidth(),
                                                         horizontalArrangement = Arrangement.SpaceBetween,
                                                         verticalAlignment = Alignment.CenterVertically
                                                     ) {
                                                         Text(lbl, color = DarkSlateText, fontWeight = FontWeight.SemiBold)
                                                         Text(
                                                             "$count samples",
                                                             color = SlateText,
                                                             style = MaterialTheme.typography.bodySmall
                                                         )
                                                     }
                                                 },
                                                 onClick = {
                                                     gestureLabel = lbl
                                                     showExistingLabelDropdown = false
                                                 }
                                             )
                                         }
                                     }
                                 }

                                 Spacer(modifier = Modifier.height(8.dp))
                                 Text(
                                     text = "TAP PREVIOUS LABEL TO ADD MORE SAMPLES:",
                                     style = MaterialTheme.typography.labelSmall,
                                     fontWeight = FontWeight.Bold,
                                     color = SlateText
                                 )
                                 Spacer(modifier = Modifier.height(4.dp))
                                 LazyRow(
                                     horizontalArrangement = Arrangement.spacedBy(6.dp),
                                     modifier = Modifier.fillMaxWidth()
                                 ) {
                                     items(recordCsvSummary.keys.toList()) { lbl ->
                                         val isSelected = (gestureLabel.trim().equals(lbl.trim(), ignoreCase = true))
                                         AssistChip(
                                             onClick = { if (!isRecording) gestureLabel = lbl },
                                             label = {
                                                 Text(
                                                     text = "$lbl (${recordCsvSummary[lbl] ?: 0})",
                                                     style = MaterialTheme.typography.bodySmall,
                                                     fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                 )
                                             },
                                             colors = AssistChipDefaults.assistChipColors(
                                                 containerColor = if (isSelected) ElectricCyan.copy(alpha = 0.25f) else ObsidianBg,
                                                 labelColor = if (isSelected) ElectricCyan else DarkSlateText
                                             ),
                                             border = BorderStroke(
                                                 1.dp,
                                                 if (isSelected) ElectricCyan else CardBorder.copy(alpha = 0.4f)
                                             )
                                         )
                                     }
                                 }
                             }

                            Spacer(modifier = Modifier.height(24.dp))

                            val isConnected = (connectionState == BleConnectionState.CONNECTED)

                            if (!isConnected) {
                                Text(
                                    text = "🔌 CONNECT GLOVE HARDWARE TO COLLECT DATA",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = CrimsonRed,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                // Live per-sensor health — green = sensor OK, red =
                                // not reporting. Check all 6 are green before recording.
                                SensorHealthIndicator(viewModel)
                                Spacer(modifier = Modifier.height(20.dp))

                                if (isRecording) {
                                    val pulseTransition = rememberInfiniteTransition(label = "pulse")
                                    val pulseScale by pulseTransition.animateFloat(
                                        initialValue = 0.98f,
                                        targetValue = 1.02f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(800, easing = EaseInOutSine),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "pulse"
                                    )

                                    Text(
                                        text = "🔴 RECORDING: $recordedCount samples captured",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = CrimsonRed,
                                        modifier = Modifier.scale(pulseScale)
                                    )

                                    // Live recording-quality readout: motion energy (gyro RMS)
                                    // plus how many frames the settle/health gates dropped, so
                                    // the user records clean, consistent takes.
                                    val gyro = livePacket?.wristGyro
                                    val motion = if (gyro != null && gyro.size >= 3)
                                        kotlin.math.sqrt(
                                            (gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2]).toFloat()
                                        ) else 0f
                                    val moving = motion >= 50f
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = (if (moving) "🟢 MOTION" else "⚪ STILL") +
                                            "  ·  energy ${motion.toInt()} dps" +
                                            (if (skippedCount > 0) "  ·  skipped $skippedCount" else ""),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (moving) AuroraGreen else SlateText,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "Tip: dynamic gestures should read MOTION while signing; " +
                                            "static handshapes (RFC) should read STILL.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SlateText.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Text(
                                        text = recordingStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (recordingStatus.contains("Saved")) AuroraGreen else SlateText,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    if (isRecording) {
                                        viewModel.stopRecording()
                                        recordCsvSummary = analyzeCsvDataset(context, csvFileName)
                                    } else {
                                        viewModel.startRecording(selectedModelType, csvFileName, gestureLabel)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRecording) CrimsonRed else ElectricCyan,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                enabled = isConnected,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (isRecording) "STOP & SAVE RECORDING" else "START DATA COLLECTION",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    datasetSummary = analyzeCsvDataset(context, csvFileName)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ObsidianBg,
                                    contentColor = DarkSlateText
                                ),
                                border = BorderStroke(1.dp, CardBorder.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isRecording,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("ANALYZE CSV DATASET", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    clearAllDatasets(context)
                                    datasetSummary = emptyMap()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CrimsonRed.copy(alpha = 0.15f),
                                    contentColor = CrimsonRed
                                ),
                                border = BorderStroke(1.dp, CrimsonRed.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isRecording,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("CLEAR ALL DATASETS", fontWeight = FontWeight.Bold)
                            }

                            if (datasetSummary.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clayInset(ClayShapeMedium, SubtleBg)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "GESTURE BALANCING BREAKDOWN",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = ElectricCyan
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        val totalSamples = datasetSummary.values.sum()
                                        Text(
                                            text = "Total samples: $totalSamples across ${datasetSummary.size} gestures",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SlateText
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        datasetSummary.forEach { (label, count) ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(text = label, color = DarkSlateText, style = MaterialTheme.typography.bodySmall)
                                                Text(
                                                    text = "$count samples",
                                                    color = ElectricCyan,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // TAB 1: MODEL TRAINING STATION
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clayCard(ClayShapeLarge)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "MODEL TRAINING STATION",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Select your target architecture. RandomForest (RFC), Temporal Convolutional Network (TCN), and LSTM models can all be trained directly on-device from the same CSV dataset.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateText,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))

                            // Model selector tabs
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { trainingModelType = RecordingModelType.RFC },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (trainingModelType == RecordingModelType.RFC) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent,
                                        contentColor = if (trainingModelType == RecordingModelType.RFC) ElectricCyan else SlateText
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (trainingModelType == RecordingModelType.RFC) ElectricCyan else CardBorder.copy(alpha = 0.1f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("RFC", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { trainingModelType = RecordingModelType.TCN },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (trainingModelType == RecordingModelType.TCN) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent,
                                        contentColor = if (trainingModelType == RecordingModelType.TCN) ElectricCyan else SlateText
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (trainingModelType == RecordingModelType.TCN) ElectricCyan else CardBorder.copy(alpha = 0.1f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("TCN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { trainingModelType = RecordingModelType.LSTM },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (trainingModelType == RecordingModelType.LSTM) ElectricCyan.copy(alpha = 0.15f) else Color.Transparent,
                                        contentColor = if (trainingModelType == RecordingModelType.LSTM) ElectricCyan else SlateText
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (trainingModelType == RecordingModelType.LSTM) ElectricCyan else CardBorder.copy(alpha = 0.1f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("LSTM", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Dataset CSV file selection dropdown
                            Text(
                                text = "DATASET CSV FILE FOR TRAINING",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = SlateText,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = selectedCsvFile,
                                    onValueChange = { selectedCsvFile = it },
                                    label = { Text("SELECT DATASET (CSV)") },
                                    readOnly = true,
                                    modifier = Modifier.fillMaxWidth(),
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
                                            contentDescription = "Dropdown Indicator",
                                            tint = ElectricCyan
                                        )
                                    }
                                )
                                // Overlap clickable box to open dropdown
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable(enabled = !isTraining) { showCsvDropdown = !showCsvDropdown }
                                )
                                DropdownMenu(
                                    expanded = showCsvDropdown,
                                    onDismissRequest = { showCsvDropdown = false },
                                    modifier = Modifier
                                        .fillMaxWidth(0.9f)
                                        .background(MidnightBg)
                                        .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(8.dp))
                                ) {
                                    if (availableCsvFiles.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("No CSV files found in TelemetryData/", color = SlateText) },
                                            onClick = { showCsvDropdown = false }
                                        )
                                    } else {
                                        availableCsvFiles.forEach { fileName ->
                                            DropdownMenuItem(
                                                text = { Text(fileName, color = DarkSlateText) },
                                                onClick = {
                                                    selectedCsvFile = fileName
                                                    showCsvDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Dynamic Selected CSV Breakdown Analyzer Card
                            if (selectedCsvFile.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clayInset(ClayShapeMedium, SubtleBg)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "SELECTED DATASET SUMMARY",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = ElectricCyan
                                            )
                                            IconButton(
                                                onClick = {
                                                    selectedCsvSummary = analyzeCsvDataset(context, selectedCsvFile)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = "Refresh summary",
                                                    tint = ElectricCyan,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        val totalSamples = selectedCsvSummary.values.sum()
                                        Text(
                                            text = "Total samples: $totalSamples across ${selectedCsvSummary.size} gestures",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SlateText
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        if (selectedCsvSummary.isEmpty()) {
                                            Text(
                                                text = "No samples or unanalyzed file.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = SlateText,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        } else {
                                            selectedCsvSummary.forEach { (label, count) ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(text = label, color = DarkSlateText, style = MaterialTheme.typography.bodySmall)
                                                        Text(
                                                            text = "$count samples",
                                                            color = SlateText,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            showDeleteConfirmDialog = Pair(label, selectedCsvFile)
                                                        },
                                                        enabled = !isTraining,
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete label $label",
                                                            tint = CrimsonRed,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Output model filename typing field
                            OutlinedTextField(
                                value = outputModelName,
                                onValueChange = { outputModelName = it },
                                label = { Text("OUTPUT MODEL NAME") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isTraining,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricCyan,
                                    unfocusedBorderColor = CardBorder.copy(alpha = 0.3f),
                                    focusedLabelColor = ElectricCyan,
                                    unfocusedLabelColor = SlateText,
                                    focusedTextColor = DarkSlateText,
                                    unfocusedTextColor = DarkSlateText
                                )
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Hyperparameters (Model-Specific)
                            if (trainingModelType == RecordingModelType.RFC) {
                                OutlinedTextField(
                                    value = estimatorsInput,
                                    onValueChange = { estimatorsInput = it },
                                    label = { Text("DECISION TREES (n_estimators)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !isTraining,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = ElectricCyan,
                                        unfocusedBorderColor = CardBorder.copy(alpha = 0.3f),
                                        focusedLabelColor = ElectricCyan,
                                        unfocusedLabelColor = SlateText,
                                        focusedTextColor = DarkSlateText,
                                        unfocusedTextColor = DarkSlateText
                                    )
                                )
                            } else {
                                OutlinedTextField(
                                    value = estimatorsInput,
                                    onValueChange = { estimatorsInput = it },
                                    label = { Text("TRAINING EPOCHS (epochs)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !isTraining,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = ElectricCyan,
                                        unfocusedBorderColor = CardBorder.copy(alpha = 0.3f),
                                        focusedLabelColor = ElectricCyan,
                                        unfocusedLabelColor = SlateText,
                                        focusedTextColor = DarkSlateText,
                                        unfocusedTextColor = DarkSlateText
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // Progress bar & Status
                            if (isTraining) {
                                Text(
                                    text = trainingStatusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ElectricCyan
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { trainingProgress },
                                    color = ElectricCyan,
                                    trackColor = CardBorder.copy(alpha = 0.1f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            } else {
                                Text(
                                    text = trainingStatusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (trainingStatusText.contains("complete", ignoreCase = true) ||
                                        trainingStatusText.contains("success", ignoreCase = true)) AuroraGreen else SlateText,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Enable guard: must have CSV file selected and model name typed
                            val canTrain = selectedCsvFile.isNotEmpty() && outputModelName.trim().isNotEmpty() && !isTraining

                            if (selectedCsvFile.isEmpty()) {
                                Text(
                                    text = "⚠️ Select a CSV file to enable training",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AmberCalibrate,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            } else if (outputModelName.trim().isEmpty()) {
                                Text(
                                    text = "⚠️ Enter an output model name to enable training",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AmberCalibrate,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }

                            Button(
                                onClick = {
                                    // Runs in TrainingManager's process-wide scope with a
                                    // foreground service + progress notification, so training
                                    // keeps going if the user leaves or closes the app.
                                    TrainingManager.startAdvanced(
                                        context = context.applicationContext,
                                        type = trainingModelType,
                                        csvFileName = selectedCsvFile,
                                        outputModelName = outputModelName.trim(),
                                        hyperParam = estimatorsInput.toIntOrNull()
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricCyan,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = CardBorder.copy(alpha = 0.2f),
                                    disabledContentColor = SlateText
                                ),
                                shape = RoundedCornerShape(12.dp),
                                enabled = canTrain,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = when (trainingModelType) {
                                        RecordingModelType.RFC -> "TRAIN RANDOM FOREST CLASSIFIER"
                                        RecordingModelType.TCN -> "TRAIN TEMPORAL CONVOLUTIONAL NETWORK"
                                        RecordingModelType.LSTM -> "TRAIN LSTM NETWORK"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // AMBER Training Terminal Console Feed
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "TRAINING LIVE TELEMETRY FEED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AmberCalibrate,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clayShadow(ClayShapeSmall, ClayShadow, ClayHighlight)
                                    .clip(ClayShapeSmall)
                                    .background(Color(0xFF070502)) // Very dark amber tint
                            ) {
                                val listState = rememberLazyListState()

                                // Auto scroll
                                LaunchedEffect(trainingLogs.size) {
                                    if (trainingLogs.isNotEmpty()) {
                                        listState.animateScrollToItem(trainingLogs.size - 1)
                                    }
                                }

                                if (trainingLogs.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Training console ready.",
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
                                        items(trainingLogs) { log ->
                                            Text(
                                                text = log,
                                                color = AmberCalibrate,
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
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Delete Gesture Label Confirmation Dialog
        showDeleteConfirmDialog?.let { (labelToDelete, fileName) ->
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = null },
                title = {
                    Text(
                        text = "DELETE GESTURE LABEL",
                        fontWeight = FontWeight.Bold,
                        color = CrimsonRed
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete all samples with the label \"$labelToDelete\" from \"$fileName\"?\n\nThis operation cannot be undone.",
                        color = DarkSlateText
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                val success = withContext(Dispatchers.IO) {
                                    deleteLabelFromCsv(context, fileName, labelToDelete)
                                }
                                if (success) {
                                    // Refresh summary
                                    selectedCsvSummary = analyzeCsvDataset(context, fileName)
                                }
                                showDeleteConfirmDialog = null
                            }
                        }
                    ) {
                        Text("DELETE", color = CrimsonRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirmDialog = null }
                    ) {
                        Text("CANCEL", color = SlateText)
                    }
                },
                containerColor = MidnightBg,
                textContentColor = DarkSlateText,
                titleContentColor = CrimsonRed
            )
        }
    }
}
