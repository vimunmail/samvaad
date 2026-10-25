package com.example.xgestalkble.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.utils.ChatLanguage
import com.example.xgestalkble.utils.LanguageConfig
import com.example.xgestalkble.utils.TextToSpeechHelper
import org.json.JSONObject

private fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
private fun loadPhoneticInput(json: JSONObject): PhoneticInputEngine {
    val languages = json.getJSONObject("languages")
    return PhoneticInputEngine(languages.keys().asSequence().associateWith { code ->
        val item = languages.getJSONObject(code)
        val vowels = item.getJSONObject("vowels")
        PhoneticScript(vowels.keys().asSequence().associateWith {
            val pair = vowels.getJSONArray(it); pair.getString(0) to pair.getString(1)
        }, item.getJSONObject("consonants").strings(), item.getJSONObject("signs").strings(),
            item.getString("virama"), item.getJSONObject("terminal").strings(),
            item.getJSONObject("words").strings(), item.getString("example"))
    })
}

@Composable
internal fun PhoneticReplyComposer(value: String, onValueChange: (String) -> Unit, onSend: (String) -> Unit,
    onQuickReply: (String) -> Unit, micListening: Boolean, onToggleMic: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("qr-chat-accessibility", 0) }
    var enabled by remember { mutableStateOf(preferences.getBoolean("phoneticInput", false)) }
    val language = LanguageConfig.voiceLanguage
    var showLanguages by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    val engine = remember(context) { runCatching {
        loadPhoneticInput(JSONObject(context.assets.open("translations/phonetic-input.json").bufferedReader().use { it.readText() }))
    }.getOrNull() }
    val available = engine?.scripts?.containsKey(language.code) == true
    var converted by remember(value, language, enabled) {
        mutableStateOf(if (enabled) engine?.convert(value, language.code) ?: value else value)
    }
    val ready = converted.isNotBlank() && converted.length <= 2000 && (!enabled || available)
    if (showTools) AlertDialog(onDismissRequest = { showTools = false }, title = { Text("Chat tools") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Language & typing", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enabled, enabled = engine != null || enabled, onCheckedChange = {
                    enabled = it
                    if (it && language.isIdentity) LanguageConfig.setVoiceLanguage(context, ChatLanguage.MALAYALAM)
                    preferences.edit().putBoolean("phoneticInput", it).apply()
                })
                Text("Type with English letters", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            }
            Box {
                TextButton(onClick = { showLanguages = true }) { Text(language.displayName) }
                DropdownMenu(expanded = showLanguages, onDismissRequest = { showLanguages = false }) {
                    ChatLanguage.entries.forEach { target ->
                        DropdownMenuItem(text = { Text("${target.displayName} · ${target.nativeName}") }, onClick = {
                            LanguageConfig.setVoiceLanguage(context, target); showLanguages = false
                            enabled = !target.isIdentity
                            preferences.edit().putBoolean("phoneticInput", enabled).apply()
                        })
                    }
                }
            }
            if (enabled) Text(if (available) "Example: ${engine?.scripts?.getValue(language.code)?.example}. Keep English words inside [[double brackets]]."
                else "Choose a writing language, or turn off phonetic typing to use your keyboard.", style = MaterialTheme.typography.labelSmall)
            Text("Check or edit the converted preview before sending.", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { TextToSpeechHelper.speak(converted.trim()); showTools = false }, enabled = ready) { Text("Speak my text") }
            Text("Quick replies", style = MaterialTheme.typography.titleSmall)
            listOf("Please repeat." to "Please repeat", "I didn’t understand. Please rephrase." to "I didn’t understand").forEach { (phrase, label) ->
                TextButton(onClick = { onQuickReply(phrase); showTools = false }) { Text(label) }
            }
        } }, confirmButton = { TextButton(onClick = { showTools = false }) { Text("Done") } })
    Column(Modifier.fillMaxWidth().background(CardBg).padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (enabled) {
            if (value.isNotBlank() && available) {
                OutlinedTextField(value = converted, onValueChange = { converted = it },
                    label = { Text("Message to send · ${language.nativeName}") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                if (converted.length > 2000) Text("Shorten the converted message to 2,000 characters.", style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showTools = true }) { Icon(Icons.Default.MoreHoriz, "Open chat tools") }
            OutlinedTextField(value = value, onValueChange = { onValueChange(it.take(2000)) },
                placeholder = { Text(if (enabled) "Type English letters…" else "Type your reply…") },
                modifier = Modifier.weight(1f), maxLines = 2)
            IconButton(onClick = onToggleMic) {
                Icon(if (micListening) Icons.Default.MicOff else Icons.Default.Mic,
                    if (micListening) "Stop listening" else "Start listening",
                    tint = if (micListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { if (ready) { onSend(converted.trim()); onValueChange("") } }, enabled = ready) { Text("Send") }
        }
    }
}
