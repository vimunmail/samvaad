package com.example.xgestalkble.ui.translation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.xgestalkble.utils.ChatLanguage
import com.example.xgestalkble.utils.GestureLabels
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.LanguageConfig
import com.example.xgestalkble.utils.TextToSpeechHelper
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelTranslationsScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val revision by GestureTranslator.revision.collectAsState()
    val status by GestureTranslator.status.collectAsState()
    val serviceError by GestureTranslator.error.collectAsState()
    var models by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var model by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(LanguageConfig.voiceLanguage.takeUnless { it.isIdentity } ?: ChatLanguage.MALAYALAM) }
    var allLanguages by remember { mutableStateOf(false) }
    var automatic by remember { mutableStateOf(GestureTranslator.automaticTranslation) }
    var modelMenu by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var newPhrase by remember { mutableStateOf("") }
    val extraPhrases = remember { mutableStateListOf<String>() }
    val phrases = (models[model].orEmpty() + extraPhrases).distinctBy(GestureTranslator::normalize)

    LaunchedEffect(Unit) {
        GestureTranslator.ensureLoaded(context)
        GestureTranslator.usePhone = true
        models = withContext(Dispatchers.IO) {
            File(context.getExternalFilesDir("models")?.absolutePath ?: context.filesDir.absolutePath)
                .listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.mapNotNull { file ->
                    runCatching {
                        val json = JSONObject(file.readText())
                        val classes = json.optJSONArray("classes") ?: json.optJSONArray("labels") ?: return@runCatching null
                        file.name to (0 until classes.length()).map { classes.getString(it) }.filterNot(GestureLabels::isRest)
                    }.getOrNull()
                }.toMap()
        }
        model = models.keys.firstOrNull().orEmpty()
    }

    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text("Model translations") }, navigationIcon = {
            IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Write the full English meaning of each sign. Translate the whole sentence, listen, correct it and save it.")
                Text("Offline AI is included. English labels are translated on this phone; no computer setup or account is needed.", style = MaterialTheme.typography.bodySmall)
                Row { Checkbox(checked = automatic, onCheckedChange = { automatic = it; GestureTranslator.automaticTranslation = it })
                    Text("Automatically translate new model labels", Modifier.padding(top = 12.dp)) }
                Box {
                    OutlinedButton(onClick = { modelMenu = true }) { Text(model.ifBlank { "Choose a model" }) }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.keys.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { model = name; modelMenu = false; extraPhrases.clear() }) }
                    }
                }
                Box {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { languageMenu = true }) { Text("Output language: ${language.displayName} · ${language.nativeName}") }
                    DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                        ChatLanguage.entries.filterNot { it.isIdentity }.forEach { value -> DropdownMenuItem(text = { Text("${value.displayName} · ${value.nativeName}") },
                            onClick = { language = value; LanguageConfig.setVoiceLanguage(context, value); languageMenu = false }) }
                    }
                }
                Row { Checkbox(checked = allLanguages, onCheckedChange = { allLanguages = it }, enabled = !busy)
                    Text("Also prepare all 12 regional languages (takes longer)", Modifier.padding(top = 12.dp)) }
                Button(enabled = !busy && phrases.isNotEmpty(), onClick = {
                    busy = true; localError = null
                    scope.launch {
                        try { GestureTranslator.prepareTranslations(phrases, if (allLanguages) ChatLanguage.entries.filterNot { it.isIdentity } else listOf(language)) }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) { localError = error.message }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Generating…" else "Translate gesture labels") }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                (localError ?: serviceError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(value = newPhrase, onValueChange = { newPhrase = it.take(300) }, label = { Text("Try a new English phrase") }, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = newPhrase.isNotBlank(), onClick = { extraPhrases.add(newPhrase.trim()); newPhrase = ""; focusManager.clearFocus() }) { Text("Add phrase for translation") }
                }
            }
            items(phrases, key = GestureTranslator::normalize) { label ->
                val text = remember(label, language, revision) { GestureTranslator.savedTranslation(label, language) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        val meaning = remember(label, revision) { GestureTranslator.englishMeaning(label) }
                        if (meaning != label) Text("English meaning: $meaning")
                        Text(text ?: "No saved ${language.displayName} meaning yet.")
                        Text(remember(label, language, revision) { GestureTranslator.translationStatus(label, language) }, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !text.isNullOrBlank(), onClick = {
                                TextToSpeechHelper.init(context); TextToSpeechHelper.speak(text.orEmpty(), language.locale)
                            }) { Text("Listen") }
                            TextButton(onClick = { focusManager.clearFocus(); editing = label }) { Text("Review / correct") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { label ->
        var english by remember(label) { mutableStateOf(GestureTranslator.englishMeaning(label)) }
        var regional by remember(label, language) { mutableStateOf(GestureTranslator.savedTranslation(label, language).orEmpty()) }
        var dialogBusy by remember(label) { mutableStateOf(false) }
        var dialogError by remember(label) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!dialogBusy) editing = null }, title = { Text("Check the sign’s meaning") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Model label: $label")
                OutlinedTextField(value = english, onValueChange = { english = it.take(300); regional = "" }, label = { Text("Complete English meaning") })
                Text("Changing the English meaning clears its old saved translations. Generate them again.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = regional, onValueChange = { regional = it.take(2000) }, label = { Text("${language.displayName} meaning") })
                TextButton(enabled = english.isNotBlank() && !dialogBusy, onClick = {
                    dialogBusy = true; dialogError = null
                    scope.launch {
                        try {
                            GestureTranslator.saveEnglishMeaning(label, english)
                            GestureTranslator.prepareTranslations(listOf(label), listOf(language))
                            regional = GestureTranslator.savedTranslation(label, language).orEmpty()
                            if (regional.isBlank()) dialogError = GestureTranslator.error.value
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { dialogError = error.message }
                        finally { dialogBusy = false }
                    }
                }) { Text(if (dialogBusy) "Generating…" else "Translate English meaning") }
                TextButton(enabled = regional.isNotBlank(), onClick = { TextToSpeechHelper.speak(regional, language.locale) }) { Text("Listen to this meaning") }
                dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, dismissButton = { TextButton(enabled = !dialogBusy, onClick = { editing = null }) { Text("Cancel") } }, confirmButton = {
            TextButton(enabled = english.isNotBlank() && regional.isNotBlank() && !dialogBusy, onClick = {
                dialogBusy = true
                scope.launch {
                    try { GestureTranslator.saveEnglishMeaning(label, english); GestureTranslator.saveReviewed(label, language, regional); editing = null }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { dialogError = error.message }
                    finally { dialogBusy = false }
                }
            }) { Text("Save reviewed meaning") }
        })
    }
}
