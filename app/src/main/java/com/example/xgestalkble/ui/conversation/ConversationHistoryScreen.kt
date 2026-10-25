package com.example.xgestalkble.ui.conversation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.xgestalkble.theme.clayCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved-conversation history: searchable list of past chats, each openable as a
 * read-only transcript that can be shared (WhatsApp/text), exported (PDF/text)
 * and analyzed. The whole record lives on-device.
 */
@Composable
fun ConversationHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    // Bumped to force a reload after a delete/rename.
    var reload by remember { mutableStateOf(0) }
    val conversations = remember(query, reload) { ConversationStore.search(context, query) }

    var open by remember { mutableStateOf<SavedConversation?>(null) }
    var analyzing by remember { mutableStateOf<SavedConversation?>(null) }
    // Gesture-audio dialog target (system-voice clips of each sign, shareable).
    var audioConv by remember { mutableStateOf<SavedConversation?>(null) }

    open?.let { conv ->
        ConversationViewerScreen(
            conv = conv,
            onBack = { open = null },
            onAnalyze = { analyzing = conv },
            modifier = modifier
        )
        analyzing?.let { AnalyzeDialog(it) { analyzing = null } }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ObsidianBg, MidnightBg)))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DarkSlateText)
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.History, null, tint = ElectricCyan)
            Spacer(Modifier.width(8.dp))
            Text(
                "CONVERSATION HISTORY",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = DarkSlateText,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search saved conversations", color = SlateText) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = ElectricCyan) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricCyan,
                unfocusedBorderColor = CardBorder.copy(alpha = 0.4f),
                focusedTextColor = DarkSlateText,
                unfocusedTextColor = DarkSlateText
            )
        )

        Spacer(Modifier.height(12.dp))

        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank())
                        "No saved conversations yet.\nFinish a chat to save a transcript here."
                    else "No conversations match \"$query\".",
                    color = SlateText,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(conversations, key = { it.id }) { conv ->
                    ConversationRow(
                        conv = conv,
                        onOpen = { open = conv },
                        onShareText = { ConversationExporter.shareText(context, conv) },
                        onSharePdf = { ConversationExporter.sharePdf(context, conv) },
                        onGestureAudio = { audioConv = conv },
                        onAnalyze = { analyzing = conv },
                        onDelete = {
                            ConversationStore.delete(context, conv.id)
                            reload++
                        }
                    )
                }
            }
        }
    }

    // Analyze from the list (no viewer open).
    if (open == null) analyzing?.let { AnalyzeDialog(it) { analyzing = null } }
    audioConv?.let { conv ->
        GestureAudioDialog(messages = conv.messages, title = conv.title) { audioConv = null }
    }
}

@Composable
private fun ConversationRow(
    conv: SavedConversation,
    onOpen: () -> Unit,
    onShareText: () -> Unit,
    onSharePdf: () -> Unit,
    onGestureAudio: () -> Unit,
    onAnalyze: () -> Unit,
    onDelete: () -> Unit
) {
    val date = remember(conv.startedAt) {
        SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault()).format(Date(conv.startedAt))
    }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .clayCard(ClayShapeLarge)
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    conv.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = DarkSlateText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(date, style = MaterialTheme.typography.labelSmall, color = SlateText)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${conv.messages.size} messages · ${conv.signCount} signed · ${conv.speechCount} spoken",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, "More", tint = SlateText)
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    modifier = Modifier.background(MidnightBg)
                ) {
                    DropdownMenuItem(
                        text = { Text("Share (WhatsApp / text)", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.Share, null, tint = AuroraGreen) },
                        onClick = { menu = false; onShareText() }
                    )
                    DropdownMenuItem(
                        text = { Text("Export as PDF", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, tint = ElectricCyan) },
                        onClick = { menu = false; onSharePdf() }
                    )
                    DropdownMenuItem(
                        text = { Text("Gesture audio (hear / share)", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.GraphicEq, null, tint = AuroraGreen) },
                        onClick = { menu = false; onGestureAudio() }
                    )
                    DropdownMenuItem(
                        text = { Text("Analyze chat", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.Analytics, null, tint = VioletAccent) },
                        onClick = { menu = false; onAnalyze() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = CrimsonRed) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = CrimsonRed) },
                        onClick = { menu = false; confirmDelete = true }
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete conversation?", color = DarkSlateText, fontWeight = FontWeight.Bold) },
            text = { Text("\"${conv.title}\" will be permanently removed from this device.", color = SlateText) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("DELETE", color = CrimsonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", color = SlateText) }
            },
            containerColor = CardBg
        )
    }
}

/** Read-only transcript of a saved conversation, with share/export/analyze. */
@Composable
private fun ConversationViewerScreen(
    conv: SavedConversation,
    onBack: () -> Unit,
    onAnalyze: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }

    if (showAudio) {
        GestureAudioDialog(messages = conv.messages, title = conv.title) { showAudio = false }
    }

    Column(modifier = modifier.fillMaxSize().background(ObsidianBg)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(CardBg).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DarkSlateText)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    conv.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = DarkSlateText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${conv.messages.size} messages",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateText
                )
            }
            IconButton(onClick = { ConversationExporter.shareText(context, conv) }) {
                Icon(Icons.Default.Share, "Share", tint = AuroraGreen)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, "More", tint = DarkSlateText)
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    modifier = Modifier.background(MidnightBg)
                ) {
                    DropdownMenuItem(
                        text = { Text("Export as PDF", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, tint = ElectricCyan) },
                        onClick = { menu = false; ConversationExporter.sharePdf(context, conv) }
                    )
                    DropdownMenuItem(
                        text = { Text("Export as text file", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.Share, null, tint = ElectricCyan) },
                        onClick = { menu = false; ConversationExporter.shareTextFile(context, conv) }
                    )
                    DropdownMenuItem(
                        text = { Text("Gesture audio (hear / share)", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.GraphicEq, null, tint = AuroraGreen) },
                        onClick = { menu = false; showAudio = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Analyze chat", color = DarkSlateText) },
                        leadingIcon = { Icon(Icons.Default.Analytics, null, tint = VioletAccent) },
                        onClick = { menu = false; onAnalyze() }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(conv.messages) { msg -> ChatBubble(msg) }
        }
    }
}

@Composable
internal fun AnalyzeDialog(conv: SavedConversation, onDismiss: () -> Unit) {
    val stats = remember(conv.id) { ConversationExporter.analyze(conv) }
    val mins = stats.durationMs / 60000
    val secs = (stats.durationMs / 1000) % 60

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Analytics, null, tint = VioletAccent)
                Spacer(Modifier.width(8.dp))
                Text("Chat analysis", color = DarkSlateText, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow("Total messages", stats.total.toString())
                StatRow("Signed by glove", stats.signCount.toString())
                StatRow("Spoken (heard)", stats.speechCount.toString())
                StatRow("Duration", "${mins}m ${secs}s")
                StatRow("Distinct signs", stats.distinctSigns.toString())
                if (stats.topSigns.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Most used signs", color = SlateText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    stats.topSigns.forEach { (label, count) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(SubtleBg).padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, color = DarkSlateText, fontWeight = FontWeight.Medium)
                            Text("×$count", color = ElectricCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE", color = ElectricCyan, fontWeight = FontWeight.Bold) }
        },
        containerColor = CardBg
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = SlateText)
        Text(value, color = DarkSlateText, fontWeight = FontWeight.Bold)
    }
}
