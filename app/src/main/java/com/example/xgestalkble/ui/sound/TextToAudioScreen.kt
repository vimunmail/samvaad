package com.example.xgestalkble.ui.sound

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.xgestalkble.ui.conversation.GestureAudioExporter
import com.example.xgestalkble.utils.ChatLanguage
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.LanguageConfig
import com.example.xgestalkble.utils.TextToSpeechHelper
import com.example.xgestalkble.utils.TtsVoiceGender
import com.example.xgestalkble.utils.TtsVoiceTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class GeneratedAudioItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val language: ChatLanguage,
    val gender: TtsVoiceGender,
    val tone: TtsVoiceTone,
    val file: File,
    val timestamp: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TextToAudioScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onTranslationSetupClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Configuration state
    var selectedLanguage by remember { mutableStateOf(LanguageConfig.voiceLanguage) }
    var selectedGender by remember { mutableStateOf(TtsVoiceGender.FEMALE) }
    var selectedTone by remember { mutableStateOf(TtsVoiceTone.NORMAL) }
    var textInput by remember { mutableStateOf("") }
    var englishInput by remember { mutableStateOf("") }
    var translating by remember { mutableStateOf(false) }
    var translationError by remember { mutableStateOf<String?>(null) }
    var translationStatus by remember { mutableStateOf("") }
    
    var isGenerating by remember { mutableStateOf(false) }
    var langDropdownExpanded by remember { mutableStateOf(false) }
    var voiceOptionsExpanded by remember { mutableStateOf(false) }

    // List of accumulated generated audios
    val generatedAudioList = remember { mutableStateListOf<GeneratedAudioItem>() }

    // MediaPlayer state
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingItemId by remember { mutableStateOf<String?>(null) }
    var playProgress by remember { mutableFloatStateOf(0f) }
    var audioDurationMs by remember { mutableIntStateOf(0) }
    var audioCurrentMs by remember { mutableIntStateOf(0) }

    // Init TTS
    LaunchedEffect(Unit) {
        TextToSpeechHelper.init(context)
        GestureTranslator.ensureLoaded(context)
    }

    // A language change needs new output before it can be spoken in that voice.
    LaunchedEffect(selectedLanguage) { textInput = ""; translationStatus = ""; translationError = null }

    // Cleanup MediaPlayer when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Progress timer for active media player
    LaunchedEffect(playingItemId) {
        while (playingItemId != null) {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                val current = mp.currentPosition
                val total = mp.duration.coerceAtLeast(1)
                audioCurrentMs = current
                audioDurationMs = total
                playProgress = (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            } else if (mp != null && !mp.isPlaying) {
                playingItemId = null
                playProgress = 0f
            }
            delay(100)
        }
    }

    // Quick samples
    val quickSamplesEn = listOf(
        "Hello, how are you doing today?",
        "Thank you very much for your help!",
        "Please listen to this message.",
        "I am using Samvaad to communicate."
    )
    val quickSamplesMl = listOf(
        "നമസ്കാരം, സുഖമാണോ?",
        "വളരെ നന്ദി!",
        "ദയവായി ഈ സന്ദേശം കേൾക്കൂ.",
        "ഞാൻ സംസാരിക്കാൻ ജെസ്റ്റാക്ക് ഉപയോഗിക്കുന്നു."
    )

    fun stopAudioPlayback() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) mp.stop()
            mp.release()
        }
        mediaPlayer = null
        playingItemId = null
        playProgress = 0f
    }

    fun playAudioItem(item: GeneratedAudioItem) {
        if (playingItemId == item.id) {
            stopAudioPlayback()
            return
        }
        stopAudioPlayback()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(item.file))
                prepare()
                setOnCompletionListener {
                    playingItemId = null
                    playProgress = 0f
                }
                start()
            }
            mediaPlayer = mp
            audioDurationMs = mp.duration
            playingItemId = item.id
        } catch (e: Exception) {
            Toast.makeText(context, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareSingleToWhatsApp(item: GeneratedAudioItem) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", item.file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, item.text)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            GestureAudioExporter.share(context, item.file, item.text)
        }
    }

    fun shareAllToWhatsApp(files: List<File>, label: String) {
        if (files.isEmpty()) return
        try {
            val uris = ArrayList(files.map {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Gesture Audio Clips: $label")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            GestureAudioExporter.shareAll(context, files, label)
        }
    }

    fun translateEnglish(testPhone: Boolean) {
        focusManager.clearFocus()
        keyboard?.hide()
        val source = englishInput.trim()
        val target = selectedLanguage
        translating = true
        translationError = null
        translationStatus = ""
        textInput = ""
        scope.launch {
            try {
                val translated = GestureTranslator.translateText(source, target, testPhone = testPhone)
                if (selectedLanguage == target && englishInput.trim() == source) {
                    textInput = translated
                    translationStatus = if (testPhone) "Translated on this phone. Review the meaning before speaking."
                        else "${target.displayName} text ready. Check or edit the meaning before speaking."
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { translationError = error.message ?: "Please reword the English sentence and try again." }
            finally { translating = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Text Translation & Audio",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            text = "Translate and speak on your phone — works offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── CARD 2: Text Input Field & Sample Chips ──
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = englishInput,
                        onValueChange = { englishInput = it.take(300); translationError = null; textInput = ""; translationStatus = "" },
                        label = { Text("1. English text") },
                        placeholder = { Text("Example: Why didn’t you come?") },
                        supportingText = { Text("Use a complete sentence. ${englishInput.length}/300") },
                        enabled = !translating,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("2. Output language", fontWeight = FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !translating && !isGenerating,
                            onClick = { langDropdownExpanded = true }) {
                            Text("${selectedLanguage.displayName} · ${selectedLanguage.nativeName}", Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, "Choose output language")
                        }
                        DropdownMenu(expanded = langDropdownExpanded, onDismissRequest = { langDropdownExpanded = false }) {
                            ChatLanguage.entries.forEach { lang ->
                                DropdownMenuItem(text = { Text("${lang.displayName} · ${lang.nativeName}") }, onClick = {
                                    selectedLanguage = lang
                                    LanguageConfig.setVoiceLanguage(context, lang)
                                    langDropdownExpanded = false
                                })
                            }
                        }
                    }
                    Button(modifier = Modifier.fillMaxWidth(),
                        enabled = englishInput.isNotBlank() && !translating && !isGenerating,
                        onClick = { translateEnglish(false) }) {
                        if (translating) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(if (translating) "Translating on your phone…" else "Translate to ${selectedLanguage.displayName}")
                    }
                    if (translating) Text("The first translation loads the offline model. Please wait.", style = MaterialTheme.typography.bodySmall)
                    if (translationStatus.isNotBlank()) Text(translationStatus, style = MaterialTheme.typography.bodySmall)
                    translationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "3. ${selectedLanguage.displayName} result",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        if (textInput.isNotEmpty()) {
                            IconButton(
                                onClick = { textInput = "" },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear text")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        enabled = !translating && !isGenerating,
                        placeholder = {
                            Text(
                                if (selectedLanguage == ChatLanguage.MALAYALAM)
                                    "പരിഭാഷ ഇവിടെ കാണാം"
                                else
                                    "Your translation appears here. You can edit it."
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${textInput.length} chars | ${textInput.split(Regex("\\s+")).filter { it.isNotBlank() }.size} words",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${selectedLanguage.displayName} (${selectedGender.label})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                }
            }

            // ── CARD 3: Action Buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Listen Live Button
                OutlinedButton(
                    enabled = !translating && !isGenerating && textInput.isNotBlank(),
                    onClick = {
                        if (textInput.isBlank()) {
                            Toast.makeText(context, "Please enter text to listen", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        if (!TextToSpeechHelper.setLanguage(selectedLanguage.locale)) {
                            translationError = "The ${selectedLanguage.displayName} voice is not installed. Open Voice options → Install voice data. Your translated text is ready."
                            return@OutlinedButton
                        }
                        TextToSpeechHelper.setVoiceStyle(selectedGender, selectedTone)
                        TextToSpeechHelper.speak(textInput, selectedLanguage.locale)
                        Toast.makeText(context, "Speaking live...", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Listen")
                }

                // Create Audio Clip Button
                Button(
                    onClick = {
                        if (textInput.isBlank()) {
                            Toast.makeText(context, "Please enter text first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isGenerating = true
                        stopAudioPlayback()
                        val currentText = textInput
                        val currentLang = selectedLanguage
                        val currentGender = selectedGender
                        val currentTone = selectedTone

                        scope.launch {
                            val file = GestureAudioExporter.synthesize(
                                context = context,
                                spoken = currentText,
                                locale = currentLang.locale,
                                gender = currentGender,
                                tone = currentTone
                            )
                            isGenerating = false
                            if (file != null) {
                                val item = GeneratedAudioItem(
                                    text = currentText,
                                    language = currentLang,
                                    gender = currentGender,
                                    tone = currentTone,
                                    file = file
                                )
                                generatedAudioList.add(0, item)
                                Toast.makeText(context, "Audio created and added to list!", Toast.LENGTH_SHORT).show()
                                playAudioItem(item)
                            } else {
                                Toast.makeText(context, "Failed to generate audio file", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !translating && !isGenerating && textInput.isNotBlank(),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Creating...")
                    } else {
                        Icon(
                            Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save audio")
                    }
                }
            }

            Text("Translation is built into the app. Speech uses your phone’s installed voice for the selected language.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { voiceOptionsExpanded = !voiceOptionsExpanded }) {
                Text(if (voiceOptionsExpanded) "Hide voice options" else "Voice options")
            }
            if (voiceOptionsExpanded) {
                TextButton(onClick = {
                    try { context.startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                    catch (_: Exception) { translationError = "Open your phone’s Settings → Text-to-speech and download the ${selectedLanguage.displayName} voice." }
                }) { Text("Install voice data") }
                Text("Voice", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TtsVoiceGender.entries.forEach { gender ->
                        FilterChip(selected = selectedGender == gender, onClick = { selectedGender = gender }, label = { Text(gender.label) })
                    }
                }
                Text("Speed and tone", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TtsVoiceTone.entries.forEach { tone ->
                        FilterChip(selected = selectedTone == tone, onClick = { selectedTone = tone }, label = { Text(tone.label) })
                    }
                }
            }
            TextButton(enabled = englishInput.isNotBlank() && !selectedLanguage.isIdentity && !translating && !isGenerating,
                onClick = { translateEnglish(true) }) { Text("Test offline AI with this sentence") }
            TextButton(onClick = onTranslationSetupClick) { Text("Translate gesture model labels") }

            // ── CARD 4: Generated Audios List & Share All Controls ──
            AnimatedVisibility(
                visible = generatedAudioList.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Header for Generated List & Share All Actions
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                RoundedCornerShape(16.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Generated Audio Clips (${generatedAudioList.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "Share all created audios at once to WhatsApp or Mail",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        stopAudioPlayback()
                                        generatedAudioList.clear()
                                    }
                                ) {
                                    Text(
                                        text = "Clear All",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            // Share All Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Share All to WhatsApp
                                Button(
                                    onClick = {
                                        shareAllToWhatsApp(
                                            files = generatedAudioList.map { it.file },
                                            label = "${generatedAudioList.size} Clips"
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF25D366),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Share All to WhatsApp",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                // Share All via General Chooser (Mail, Bluetooth, etc.)
                                Button(
                                    onClick = {
                                        GestureAudioExporter.shareAll(
                                            context = context,
                                            files = generatedAudioList.map { it.file },
                                            title = "${generatedAudioList.size} Audio Clips"
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Share All (Mail/Apps)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    // Individual Audio Items List
                    Text(
                        text = "Created Audios List (Click item to play or share individually):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    generatedAudioList.forEachIndexed { index, item ->
                        val isThisPlaying = playingItemId == item.id

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isThisPlaying)
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                else
                                    MaterialTheme.colorScheme.surfaceContainer
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (isThisPlaying) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(14.dp)
                                )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        // Play / Pause Circle Action Button
                                        IconButton(
                                            onClick = { playAudioItem(item) },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isThisPlaying) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                                )
                                        ) {
                                            Icon(
                                                if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = if (isThisPlaying) "Pause" else "Play",
                                                tint = if (isThisPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.text,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 15.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${item.language.displayName} • ${item.gender.label} Voice • ${item.file.length() / 1024} KB",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Single WhatsApp Share
                                        IconButton(
                                            onClick = { shareSingleToWhatsApp(item) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Share,
                                                contentDescription = "Share item",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        // Delete Item
                                        IconButton(
                                            onClick = {
                                                if (playingItemId == item.id) stopAudioPlayback()
                                                generatedAudioList.remove(item)
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete item",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                // Active item playback progress
                                if (isThisPlaying) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        LinearProgressIndicator(
                                            progress = { playProgress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Playing clip #${generatedAudioList.size - index}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "${audioCurrentMs / 1000}s / ${audioDurationMs / 1000}s",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
