package com.example.xgestalkble.ui.conversation

import android.content.Context
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale

private data class SignVideo(val path: String, val language: String)
private fun normalizedPhrase(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.US).trim().replace(Regex("[.!?,;:]+$"), "").trim().replace(Regex("\\s+"), " ")

/** Exact reviewed phrase lookup, not word-by-word sign-language translation. */
private fun findVideo(context: Context, text: String): SignVideo? = runCatching {
    val videos = JSONObject(context.assets.open("sign-media.json").bufferedReader().use { it.readText() }).getJSONArray("videos")
    (0 until videos.length()).firstNotNullOfOrNull { index ->
        val video = videos.getJSONObject(index)
        val path = video.getString("path")
        val phrases = video.getJSONArray("phrases")
        if (video.getString("signLanguage") == "ISL" && Regex("^videos/[A-Za-z0-9_/-]+\\.mp4$").matches(path)
            && !path.contains("..") && (0 until phrases.length()).any { normalizedPhrase(phrases.getString(it)) == normalizedPhrase(text) })
            SignVideo(path, "ISL") else null
    }
}.getOrNull()

@Composable
internal fun SignVideoPreview(text: String) {
    val context = LocalContext.current
    val video = remember(text) { findVideo(context, text) } ?: return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("▶ Play sign video · ${video.language}") }
    if (open) {
        val fileResult by produceState<Result<File>?>(null, video.path) {
            value = withContext(Dispatchers.IO) { runCatching {
                val directory = File(context.cacheDir, "sign_videos").apply { mkdirs() }
                val destination = File(directory, "${video.path.hashCode()}.mp4")
                if (!destination.exists()) context.assets.open(video.path).use { input -> destination.outputStream().use { input.copyTo(it) } }
                destination
            } }
        }
        var loaded by remember { mutableStateOf(false) }
        var playbackError by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<VideoView?>(null) }
        DisposableEffect(Unit) { onDispose { player?.stopPlayback() } }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("${video.language} sign video") },
            text = {
                Column {
                    Text(text)
                    val localFile = fileResult?.getOrNull()
                    if (localFile != null && !playbackError) AndroidView(
                        factory = { viewContext -> VideoView(viewContext).apply {
                            player = this
                            setMediaController(MediaController(viewContext).also { it.setAnchorView(this) })
                            setOnPreparedListener { loaded = true; start() }
                            setOnErrorListener { _, _, _ -> playbackError = true; true }
                            setVideoURI(Uri.fromFile(localFile))
                        } }, modifier = Modifier.fillMaxWidth().height(260.dp))
                    if (!loaded && !playbackError && fileResult?.isFailure != true) CircularProgressIndicator()
                    if (playbackError || fileResult?.isFailure == true) Text("Video unavailable. The original message is still visible.")
                }
            }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
    }
}
