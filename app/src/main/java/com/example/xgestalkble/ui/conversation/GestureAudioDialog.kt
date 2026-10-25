package com.example.xgestalkble.ui.conversation

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.xgestalkble.theme.AuroraGreen
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.CrimsonRed
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.SubtleBg
import com.example.xgestalkble.utils.LanguageConfig
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.Locale

/** One playable/shareable gesture clip: the English label + what TTS speaks. */
private data class GestureAudioEntry(val label: String, val spoken: String)

/**
 * "Gesture audio" dialog: system-generated voice recordings of every gesture in
 * the conversation. Each distinct sign is synthesized once (in the current voice
 * language) to an .m4a clip that can be replayed in-app and shared individually
 * — or all at once — via the share sheet (WhatsApp, Gmail, Drive, …).
 */
@Composable
fun GestureAudioDialog(
    messages: List<ChatMessage>,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val voiceLocale = LanguageConfig.voiceLanguage.locale

    // Distinct signed gestures, in first-use order. Speak the same phrase the
    // live chat spoke (translation when one exists, else the English label).
    val entries = remember(messages) {
        messages
            .filter { it.source == "sign" && !it.text.trim().uppercase(Locale.US).startsWith("REST") }
            .map { GestureAudioEntry(label = it.text, spoken = it.translation ?: it.text) }
            .distinctBy { it.spoken }
    }

    // spoken text → ready audio file. Filled in as generation progresses.
    val files = remember { mutableStateMapOf<String, File>() }
    val failed = remember { mutableStateMapOf<String, Boolean>() }
    var doneCount by remember { mutableStateOf(0) }
    var engineError by remember { mutableStateOf(false) }
    val generating = doneCount < entries.size && !engineError

    LaunchedEffect(entries) {
        if (entries.isEmpty()) return@LaunchedEffect
        if (!GestureAudioExporter.ensureReady(context)) {
            engineError = true
            return@LaunchedEffect
        }
        entries.forEach { entry ->
            val f = GestureAudioExporter.synthesize(context, entry.spoken, voiceLocale)
            if (f != null) files[entry.spoken] = f else failed[entry.spoken] = true
            doneCount++
        }
    }

    // In-app playback of a generated clip; only one plays at a time.
    var playingKey by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }
    fun togglePlay(key: String, file: File) {
        runCatching {
            if (playingKey == key) {
                player.stop()
                playingKey = null
            } else {
                player.reset()
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { playingKey = null }
                player.prepare()
                player.start()
                playingKey = key
            }
        }.onFailure {
            playingKey = null
            Toast.makeText(context, "Couldn't play audio", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, null, tint = ElectricCyan)
                Spacer(Modifier.width(8.dp))
                Text("Gesture audio", color = DarkSlateText, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    entries.isEmpty() -> Text(
                        "No signed gestures in this conversation yet. Sign something first, then come back here.",
                        color = SlateText
                    )
                    engineError -> Text(
                        "The text-to-speech engine isn't available, so audio can't be generated.",
                        color = CrimsonRed
                    )
                    else -> {
                        Text(
                            "Voice recordings of each sign, spoken by the app in " +
                                "${LanguageConfig.voiceLanguage.nativeName}. Play them here or share " +
                                "each clip (or all) to WhatsApp, Gmail, Drive…",
                            color = SlateText,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (generating) {
                            LinearProgressIndicator(
                                progress = { if (entries.isEmpty()) 0f else doneCount / entries.size.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = ElectricCyan,
                                trackColor = CardBorder.copy(alpha = 0.2f)
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(entries, key = { it.spoken }) { entry ->
                                val file = files[entry.spoken]
                                val didFail = failed[entry.spoken] == true
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SubtleBg)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { file?.let { togglePlay(entry.spoken, it) } },
                                        enabled = file != null,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        when {
                                            file == null && !didFail -> CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = ElectricCyan
                                            )
                                            else -> Icon(
                                                if (playingKey == entry.spoken) Icons.Default.Stop
                                                else Icons.Default.PlayArrow,
                                                contentDescription = if (playingKey == entry.spoken) "Stop" else "Play",
                                                tint = if (didFail) CardBorder else ElectricCyan
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                        Text(
                                            entry.label,
                                            color = DarkSlateText,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val sub = when {
                                            didFail -> "Audio failed — voice may be missing"
                                            entry.spoken != entry.label -> entry.spoken
                                            else -> null
                                        }
                                        if (sub != null) {
                                            Text(
                                                sub,
                                                color = if (didFail) CrimsonRed else SlateText,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            file?.let { GestureAudioExporter.share(context, it, entry.label) }
                                        },
                                        enabled = file != null,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = "Share ${entry.label} audio",
                                            tint = if (file != null) AuroraGreen else CardBorder
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ready = entries.mapNotNull { files[it.spoken] }
                    if (ready.isEmpty()) {
                        Toast.makeText(context, "No audio ready yet", Toast.LENGTH_SHORT).show()
                    } else {
                        GestureAudioExporter.shareAll(context, ready, title)
                    }
                },
                enabled = files.isNotEmpty()
            ) {
                Text("SHARE ALL", color = if (files.isNotEmpty()) AuroraGreen else CardBorder, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
        }
    )
}
