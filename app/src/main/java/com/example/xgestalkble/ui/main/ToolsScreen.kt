package com.example.xgestalkble.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.xgestalkble.ble.BleConnectionState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import androidx.navigation3.runtime.NavKey
import com.example.xgestalkble.DataCollection
import com.example.xgestalkble.GestureToVoice
import com.example.xgestalkble.Prediction
import com.example.xgestalkble.SerialMonitor
import com.example.xgestalkble.TeachGestures
import com.example.xgestalkble.TextToAudio
import com.example.xgestalkble.ModelTranslations
import com.example.xgestalkble.theme.SubtleBg
import com.example.xgestalkble.ui.conversation.ConversationExporter
import com.example.xgestalkble.ui.conversation.ConversationStore
import com.example.xgestalkble.ui.conversation.SavedConversation
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.MidnightBg
import com.example.xgestalkble.theme.ObsidianBg
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.VioletAccent
import com.example.xgestalkble.theme.ClayShapeLarge
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayInset

/**
 * Advanced tools, intentionally kept OFF the home screen so the everyday
 * deaf/mute user sees only "talk" features. Everything technical lives here:
 * sign translation, live prediction, on-device model training, the serial
 * monitor and file management.
 */
@Composable
fun ToolsScreen(
    onItemClick: (NavKey) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(context.applicationContext) }
    val connectionState by viewModel.connectionState.collectAsState()
    var datasetFiles by remember { mutableStateOf(listDatasetEntries(context)) }
    var modelFiles by remember { mutableStateOf(listModelEntries(context)) }
    var chatFiles by remember { mutableStateOf(listChatEntries(context)) }
    var pendingDelete by remember { mutableStateOf<Pair<ManagedFile, FileCategory>?>(null) }
    var pendingRename by remember { mutableStateOf<Pair<ManagedFile, FileCategory>?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showRecalibrate by remember { mutableStateOf(false) }

    fun refreshFiles() {
        datasetFiles = listDatasetEntries(context)
        modelFiles = listModelEntries(context)
        chatFiles = listChatEntries(context)
    }

    // System file picker for importing shared models (.json) and datasets (.csv)
    // received from another phone (WhatsApp downloads, Files app, USB, …).
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            Toast.makeText(context, importManagedFiles(context, uris), Toast.LENGTH_LONG).show()
            refreshFiles()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshFiles()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ObsidianBg, MidnightBg)))
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to home", tint = DarkSlateText)
            }
            Column {
                Text(
                    "Tools & Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = DarkSlateText
                )
                Text(
                    "Advanced features and device management",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateText
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionLabel("TRANSLATE & PREDICT")
            Spacer(Modifier.height(12.dp))
            SecondaryBanner(
                icon = Icons.Default.RecordVoiceOver,
                title = "Text Translation & Audio",
                description = "Translate English into regional text, test IndicTrans2, listen and share audio clips.",
                onClick = { onItemClick(TextToAudio) }
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.RecordVoiceOver,
                    gradient = listOf(VioletAccent, ElectricCyan),
                    title = "Translate signs",
                    description = "Turn each sign into spoken voice with text and pictures.",
                    onClick = { onItemClick(GestureToVoice) }
                )
                FeatureCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Insights,
                    gradient = listOf(AuroraGreen, ElectricCyan),
                    title = "Live prediction",
                    description = "Watch the glove read single signs in real time.",
                    onClick = { onItemClick(Prediction) }
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("MODEL SETUP")
            Spacer(Modifier.height(12.dp))
            SecondaryBanner(
                icon = Icons.Default.Edit,
                title = "Model translations",
                description = "Translate with your computer or Google, check meanings and save corrections.",
                onClick = { onItemClick(ModelTranslations) }
            )
            Spacer(Modifier.height(12.dp))
            SecondaryBanner(
                icon = Icons.Default.School,
                title = "Teach my signs (easy)",
                description = "Simple 3-step guide: add a sign, record it, train. Made for everyone.",
                onClick = { onItemClick(TeachGestures) }
            )
            Spacer(Modifier.height(12.dp))
            SecondaryBanner(
                icon = Icons.Default.ModelTraining,
                title = "Record & train (advanced)",
                description = "Full control: CSV datasets, RFC / TCN / LSTM models, training settings.",
                onClick = { onItemClick(DataCollection) }
            )
            Spacer(Modifier.height(12.dp))
            SecondaryBanner(
                icon = Icons.Default.Terminal,
                title = "Serial monitor",
                description = "Inspect the live BLE telemetry stream from the glove.",
                onClick = { onItemClick(SerialMonitor) }
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("DEVICE")
            Spacer(Modifier.height(12.dp))
            SecondaryBanner(
                icon = Icons.Default.Tune,
                title = "Recalibrate glove",
                description = "Re-teach finger open/fist range — flat & open (5s), then fist (5s).",
                onClick = { showRecalibrate = true }
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("FILE MANAGER")
            Spacer(Modifier.height(12.dp))
            FileManagerCard(
                datasets = datasetFiles,
                models = modelFiles,
                chats = chatFiles,
                onShare = { entry, category -> shareManagedFile(context, entry, category) },
                onDelete = { entry, category -> pendingDelete = entry to category },
                onRename = { entry, category ->
                    pendingRename = entry to category
                    renameText = entry.displayName
                },
                onImport = { importLauncher.launch(arrayOf("*/*")) }
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showRecalibrate) {
        RecalibrationDialog(
            connected = connectionState == BleConnectionState.CONNECTED,
            onStart = { viewModel.recalibrateGlove() },
            onDismiss = { showRecalibrate = false }
        )
    }

    if (pendingDelete != null) {
        val (entry, category) = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${category.singular}", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete '${entry.displayName}' from your device?") },
            confirmButton = {
                TextButton(onClick = {
                    val success = when (category) {
                        FileCategory.DATASETS -> deleteSpecificCsv(context, entry.fileName)
                        FileCategory.MODELS -> deleteSpecificModel(context, entry.fileName)
                        FileCategory.CHATS -> {
                            ConversationStore.delete(context, entry.fileName.removeSuffix(".json"))
                            true
                        }
                    }
                    if (success) {
                        Toast.makeText(context, "Deleted ${entry.displayName}", Toast.LENGTH_SHORT).show()
                        refreshFiles()
                    } else {
                        Toast.makeText(context, "Failed to delete ${entry.displayName}", Toast.LENGTH_SHORT).show()
                    }
                    pendingDelete = null
                }) {
                    Text("DELETE", color = CrimsonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("CANCEL") } },
            containerColor = CardBg,
            titleContentColor = DarkSlateText,
            textContentColor = SlateText
        )
    }

    if (pendingRename != null) {
        val (entry, category) = pendingRename!!
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Rename ${category.singular}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a new name for '${entry.displayName}':")
                    androidx.compose.material3.OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = CardBorder.copy(alpha = 0.4f),
                            focusedLabelColor = ElectricCyan,
                            unfocusedLabelColor = SlateText,
                            focusedTextColor = DarkSlateText,
                            unfocusedTextColor = DarkSlateText
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val success = if (category == FileCategory.CHATS) {
                        ConversationStore.rename(context, entry.fileName.removeSuffix(".json"), renameText)
                        true
                    } else {
                        renameSpecificFile(context, category, entry.fileName, renameText)
                    }
                    if (success) {
                        Toast.makeText(context, "Renamed successfully", Toast.LENGTH_SHORT).show()
                        refreshFiles()
                    } else {
                        Toast.makeText(context, "Failed to rename (name might exist or be invalid)", Toast.LENGTH_SHORT).show()
                    }
                    pendingRename = null
                }) {
                    Text("RENAME", color = ElectricCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text("CANCEL") } },
            containerColor = CardBg,
            titleContentColor = DarkSlateText,
            textContentColor = SlateText
        )
    }
}

// ═══════════════════════ FILE MANAGER ═══════════════════════
// One organised place for everything the app stores on the device:
// recorded CSV datasets, trained model files and saved conversations.

internal enum class FileCategory(
    val title: String,
    val singular: String,
    val color: Color,
    val icon: ImageVector,
    val emptyMessage: String
) {
    DATASETS(
        "Datasets", "dataset", ElectricCyan, Icons.Default.Description,
        "No CSV recordings yet — capture gesture data from Record & train."
    ),
    MODELS(
        "Models", "model", AuroraGreen, Icons.Default.ModelTraining,
        "No trained models yet — build one from Record & train."
    ),
    CHATS(
        "Chats", "conversation", VioletAccent, Icons.AutoMirrored.Filled.Chat,
        "No saved conversations yet — chats are saved when you leave the conversation screen."
    )
}

internal data class ManagedFile(
    val displayName: String,
    val fileName: String,          // physical file name (delete / share)
    val detail: String,            // "12.4 KB · 2 Jul 2026" or chat stats
    val file: File?,               // physical file, when it exists
    val conv: SavedConversation? = null
)

private fun prettySize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576f)
    bytes >= 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1_024f)
    else -> "$bytes B"
}

private fun fileDetail(f: File): String {
    val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(f.lastModified()))
    return "${prettySize(f.length())} · $date"
}

internal fun listDatasetEntries(context: Context): List<ManagedFile> {
    val dir = context.getExternalFilesDir("TelemetryData") ?: return emptyList()
    return dir.listFiles { f -> f.isFile && f.extension.equals("csv", true) }
        ?.sortedByDescending { it.lastModified() }
        ?.map { ManagedFile(it.nameWithoutExtension, it.name, fileDetail(it), it) }
        ?: emptyList()
}

internal fun listModelEntries(context: Context): List<ManagedFile> {
    val dir = context.getExternalFilesDir("models") ?: return emptyList()
    return dir.listFiles { f -> f.isFile && f.extension.equals("json", true) }
        ?.sortedByDescending { it.lastModified() }
        ?.map { ManagedFile(it.nameWithoutExtension, it.name, fileDetail(it), it) }
        ?: emptyList()
}

internal fun listChatEntries(context: Context): List<ManagedFile> {
    val stamp = SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault())
    return ConversationStore.list(context).map { conv ->
        val f = ConversationStore.fileFor(context, conv.id)
        ManagedFile(
            displayName = conv.title,
            fileName = "${conv.id}.json",
            detail = "${stamp.format(Date(conv.startedAt))} · " +
                "${conv.signCount} signed · ${conv.speechCount} spoken",
            file = f.takeIf { it.exists() },
            conv = conv
        )
    }
}

/**
 * Share a managed file: chats go out as a readable transcript (WhatsApp-ready);
 * datasets and models are shared as raw file attachments via the FileProvider.
 */
internal fun shareManagedFile(context: Context, entry: ManagedFile, category: FileCategory) {
    if (category == FileCategory.CHATS) {
        entry.conv?.let { ConversationExporter.shareText(context, it) }
        return
    }
    val file = entry.file ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mime = if (file.extension.equals("csv", true)) "text/csv" else "application/json"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Share ${file.name}")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

private fun displayNameOf(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

// Ceiling for a single imported file. Models are a few MB, big CSV datasets a
// few tens of MB; the cap stops a hostile content provider from streaming
// gigabytes into memory (OOM crash).
private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

/**
 * SECURITY: the display name of a shared file is fully controlled by the
 * sending app, so treat it as hostile input. Keep only a safe basename — no
 * path separators, no "..", no exotic characters — so the import can never
 * write outside its target directory (path traversal) or overwrite app files.
 */
private fun sanitizeImportName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base
        .replace(Regex("[^A-Za-z0-9._ ()\\-]"), "_")
        .trim()
        .trimStart('.')          // kills "..", ".hidden" and "..json" tricks
    return cleaned.take(64).ifEmpty { "import_${System.currentTimeMillis()}" }
}

/** Read a content stream fully, but refuse anything over [MAX_IMPORT_BYTES]. */
private fun readAllCapped(input: java.io.InputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val r = input.read(buf)
        if (r < 0) break
        total += r
        require(total <= MAX_IMPORT_BYTES) { "file larger than 64 MB" }
        out.write(buf, 0, r)
    }
    return out.toByteArray()
}

/**
 * Copy files picked in the system document picker into the app's own storage:
 * `.json` → models/ (validated as a model file), `.csv` → TelemetryData/.
 * Never overwrites — a name collision gets a " (2)" suffix. Returns a short
 * human-readable summary for a toast.
 */
internal fun importManagedFiles(context: Context, uris: List<Uri>): String {
    var models = 0
    var datasets = 0
    var skipped = 0
    for (uri in uris) {
        try {
            require(uri.scheme == "content") { "Only shared content files can be imported" }
            val bytes = context.contentResolver.openInputStream(uri)?.use { readAllCapped(it) }
                ?: throw IllegalStateException("unreadable")
            val name = sanitizeImportName(
                displayNameOf(context, uri)
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "import_${System.currentTimeMillis()}"
            )
            // Prefer the extension; shares without one (octet-stream forwards)
            // are sniffed from the content instead.
            var ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            if (ext !in listOf("json", "csv")) {
                val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).trimStart()
                ext = when {
                    head.startsWith("{") -> "json"
                    head.lineSequence().firstOrNull()?.contains(',') == true -> "csv"
                    else -> throw IllegalArgumentException("unrecognized file type: $name")
                }
            }
            if (ext == "json") {
                // Must look like a trained model, not some random JSON.
                // LSTM/TCN models carry "labels"; RFC models carry "classes".
                val obj = JSONObject(String(bytes, Charsets.UTF_8))
                require(obj.has("labels") || obj.has("classes")) { "not a Samvaad model file" }
            }
            val destDir = (if (ext == "json") context.getExternalFilesDir("models")
                else context.getExternalFilesDir("TelemetryData"))
                ?: throw IllegalStateException("storage unavailable")
            destDir.mkdirs()
            val base = name.substringBeforeLast('.').ifEmpty { "import" }
            var target = File(destDir, "$base.$ext")
            var n = 2
            while (target.exists()) { target = File(destDir, "$base ($n).$ext"); n++ }
            target.writeBytes(bytes)
            if (ext == "json") models++ else datasets++
        } catch (e: Exception) {
            android.util.Log.w("SamvaadImport", "Import rejected (${e.javaClass.simpleName})")
            skipped++
        }
    }
    val parts = mutableListOf<String>()
    if (models > 0) parts.add("$models model${if (models > 1) "s" else ""}")
    if (datasets > 0) parts.add("$datasets dataset${if (datasets > 1) "s" else ""}")
    val imported = if (parts.isEmpty()) "Nothing imported" else "Imported ${parts.joinToString(" + ")}"
    return if (skipped > 0) "$imported · $skipped skipped (only model .json / dataset .csv files)" else imported
}

internal fun renameSpecificFile(context: Context, category: FileCategory, oldFileName: String, newNameWithoutExtension: String): Boolean {
    val dir = when (category) {
        FileCategory.DATASETS -> context.getExternalFilesDir("TelemetryData")
        FileCategory.MODELS -> context.getExternalFilesDir("models")
        else -> null
    } ?: return false
    val oldFile = File(dir, oldFileName)
    if (!oldFile.exists()) return false
    val ext = oldFile.extension
    val cleanName = newNameWithoutExtension.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    if (cleanName.isEmpty()) return false
    val newFileName = if (ext.isNotEmpty()) "$cleanName.$ext" else cleanName
    val newFile = File(dir, newFileName)
    if (newFile.exists()) return false
    return oldFile.renameTo(newFile)
}

@Composable
internal fun FileManagerCard(
    datasets: List<ManagedFile>,
    models: List<ManagedFile>,
    chats: List<ManagedFile>,
    onShare: (ManagedFile, FileCategory) -> Unit,
    onDelete: (ManagedFile, FileCategory) -> Unit,
    onRename: (ManagedFile, FileCategory) -> Unit,
    onImport: () -> Unit
) {
    var selected by remember { mutableStateOf(FileCategory.DATASETS) }
    val entriesFor = { cat: FileCategory ->
        when (cat) {
            FileCategory.DATASETS -> datasets
            FileCategory.MODELS -> models
            FileCategory.CHATS -> chats
        }
    }
    val entries = entriesFor(selected)
    val totalBytes = (datasets + models + chats).sumOf { it.file?.length() ?: 0L }
    val totalCount = datasets.size + models.size + chats.size

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeLarge)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: title + storage summary.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clayInset(ClayShapeSmall, ElectricCyan.copy(alpha = 0.16f))
                ) {
                    Icon(Icons.Default.Folder, "Device files", tint = ElectricCyan, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "File Manager",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = DarkSlateText
                    )
                    Text(
                        "$totalCount files · ${prettySize(totalBytes)} on device",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = SlateText
                    )
                }
                // Import a shared model (.json) or dataset (.csv) from anywhere
                // on the phone — Downloads, WhatsApp, USB copies.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AuroraGreen.copy(alpha = 0.14f))
                        .border(BorderStroke(1.dp, AuroraGreen.copy(alpha = 0.5f)), RoundedCornerShape(10.dp))
                        .clickable { onImport() }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Import model or dataset",
                        tint = AuroraGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Text("IMPORT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AuroraGreen)
                }
            }

            // Category tabs — Datasets | Models | Chats, each with its count.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileCategory.entries.forEach { cat ->
                    val active = cat == selected
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) cat.color.copy(alpha = 0.16f) else SubtleBg)
                            .border(
                                BorderStroke(1.dp, if (active) cat.color else CardBorder.copy(alpha = 0.1f)),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { selected = cat }
                            .padding(vertical = 9.dp)
                    ) {
                        Text(
                            text = "${cat.title} · ${entriesFor(cat).size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) cat.color else SlateText,
                            maxLines = 1
                        )
                    }
                }
            }

            // File list for the selected category.
            if (entries.isEmpty()) {
                Text(
                    text = selected.emptyMessage,
                    fontSize = 11.sp,
                    color = SlateText,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entries.forEach { entry ->
                        FileRow(
                            entry = entry,
                            category = selected,
                            onShare = { onShare(entry, selected) },
                            onDelete = { onDelete(entry, selected) },
                            onRename = { onRename(entry, selected) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: ManagedFile,
    category: FileCategory,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SubtleBg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(category.color.copy(alpha = 0.14f))
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = category.color,
                modifier = Modifier.size(15.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DarkSlateText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.detail,
                fontSize = 10.sp,
                color = SlateText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share ${entry.displayName}",
                tint = category.color.copy(alpha = 0.85f),
                modifier = Modifier.size(15.dp)
            )
        }
        IconButton(onClick = onRename, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Rename ${entry.displayName}",
                tint = category.color.copy(alpha = 0.85f),
                modifier = Modifier.size(15.dp)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete ${entry.displayName}",
                tint = CrimsonRed.copy(alpha = 0.8f),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
