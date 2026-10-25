package com.example.xgestalkble.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.xgestalkble.theme.CardBg
import com.example.xgestalkble.theme.CardBorder
import com.example.xgestalkble.theme.DarkSlateText
import com.example.xgestalkble.theme.ElectricCyan
import com.example.xgestalkble.theme.SlateText
import com.example.xgestalkble.theme.SubtleBg
import com.example.xgestalkble.theme.VioletAccent
import com.example.xgestalkble.theme.ClayShapeXL
import com.example.xgestalkble.theme.ClayShapeSmall
import com.example.xgestalkble.theme.ClayShapeChip
import com.example.xgestalkble.theme.ClayShadow
import com.example.xgestalkble.theme.ClayHighlight
import com.example.xgestalkble.theme.clayCard
import com.example.xgestalkble.theme.clayInset
import com.example.xgestalkble.theme.clayShadow
import com.example.xgestalkble.utils.ChatLanguage
import com.example.xgestalkble.utils.GestureTranslator
import com.example.xgestalkble.utils.LanguageConfig
import com.example.xgestalkble.utils.TextToSpeechHelper
import com.example.xgestalkble.utils.TtsVoiceGender
import com.example.xgestalkble.utils.TtsVoiceTone

/**
 * Production language picker shared by Home and Chat. Lets the user choose two
 * independent languages — one for the wearer's signs becoming voice/text, one
 * for the hearing person's speech becoming text — and persists them through
 * [LanguageConfig]. Saved meanings work offline; new English phrases use the chosen translator.
 */
@Composable
fun LanguageSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val voice = LanguageConfig.voiceLanguage
    val stt = LanguageConfig.sttLanguage

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clayCard(ClayShapeXL)
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clayShadow(ClayShapeSmall, ClayShadow, ClayHighlight, offset = 4.dp, blur = 9.dp)
                        .clip(ClayShapeSmall)
                        .background(Brush.linearGradient(listOf(ElectricCyan, VioletAccent)))
                ) {
                    Icon(Icons.Default.Language, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Column {
                    Text(
                        "Language & voice",
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp,
                        color = DarkSlateText
                    )
                    Text(
                        "Choose what this phone speaks and hears",
                        fontSize = 11.sp,
                        color = SlateText
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            LanguageChoiceRow(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "Speak my signs on this phone",
                subtitle = "Local voice & sign text language",
                selected = voice,
                onSelect = { LanguageConfig.setVoiceLanguage(context, it) }
            )

            Spacer(Modifier.height(18.dp))

            Text(
                "QR guests choose their own listening language on their phone. This setting does not change the guest's voice. Voice availability depends on each phone's installed languages.",
                fontSize = 12.sp,
                color = SlateText
            )
            Spacer(Modifier.height(12.dp))
            Text("Translate new English labels with your computer or Google in Tools → Model translations. Review and save their meanings to use them offline.", fontSize = 12.sp, color = SlateText)

            Spacer(Modifier.height(18.dp))

            VoiceStyleSection()

            Spacer(Modifier.height(18.dp))

            LanguageChoiceRow(
                icon = Icons.Default.Hearing,
                title = "Listen to speech on this phone",
                subtitle = "Choose the language being spoken",
                selected = stt,
                onSelect = { LanguageConfig.setSttLanguage(context, it) }
            )

            Spacer(Modifier.height(22.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clayShadow(ClayShapeChip, ClayShadow, ClayHighlight, offset = 4.dp, blur = 10.dp)
                    .clip(ClayShapeChip)
                    .background(ElectricCyan)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 15.dp)
            ) {
                Text("DONE", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }
}

/**
 * The spoken voice's style: gender (male / female, using a real matching TTS
 * voice when the phone has one, pitch-shifted otherwise) and a modulation
 * preset (soft, deep, high, slow…). Persisted in [LanguageConfig]; every
 * change takes effect on the very next spoken sign. The TEST button speaks a
 * sample so the user can hear the style before leaving the dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceStyleSection() {
    val context = LocalContext.current
    val gender = LanguageConfig.voiceGender
    val tone = LanguageConfig.voiceTone

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.RecordVoiceOver, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text("Voice style", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = DarkSlateText)
                Text("How the spoken voice sounds", fontSize = 10.sp, color = SlateText)
            }
            // Speak a sample with the CURRENT language + style so the choice is audible.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(ClayShapeChip)
                    .background(ElectricCyan)
                    .clickable {
                        TextToSpeechHelper.init(context.applicationContext)
                        TextToSpeechHelper.setLanguage(LanguageConfig.voiceLanguage.locale)
                        GestureTranslator.ensureLoaded(context.applicationContext)
                        val sample = GestureTranslator.translate("HELLO", LanguageConfig.voiceLanguage)
                            .ifBlank { "Hello" }
                        TextToSpeechHelper.speak(sample)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(15.dp))
                Text("TEST", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TtsVoiceGender.entries.forEach { g ->
                StyleChip(label = g.label, selected = g == gender) {
                    LanguageConfig.setVoiceStyle(context, g, tone)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TtsVoiceTone.entries.forEach { t ->
                StyleChip(label = t.label, selected = t == tone) {
                    LanguageConfig.setVoiceStyle(context, gender, t)
                }
            }
        }
    }
}

@Composable
private fun StyleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val chipMod = if (selected) {
        Modifier.clayShadow(ClayShapeChip, ClayShadow, ClayHighlight, offset = 3.dp, blur = 8.dp)
            .clip(ClayShapeChip)
            .background(ElectricCyan.copy(alpha = 0.18f))
            .border(BorderStroke(2.dp, ElectricCyan), ClayShapeChip)
    } else {
        Modifier.clip(ClayShapeChip).background(SubtleBg)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(chipMod)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = if (selected) ElectricCyan else DarkSlateText
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: ChatLanguage,
    onSelect: (ChatLanguage) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = DarkSlateText)
                Text(subtitle, fontSize = 10.sp, color = SlateText)
            }
        }
        Spacer(Modifier.height(10.dp))
        // Wrap the (now 13) languages onto as many rows as needed; chips size to
        // their content rather than splitting the width evenly.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChatLanguage.entries.forEach { lang ->
                val isSel = lang == selected
                val chipMod = if (isSel) {
                    Modifier.clayShadow(ClayShapeChip, ClayShadow, ClayHighlight, offset = 3.dp, blur = 8.dp)
                        .clip(ClayShapeChip)
                        .background(ElectricCyan.copy(alpha = 0.18f))
                        .border(BorderStroke(2.dp, ElectricCyan), ClayShapeChip)
                } else {
                    Modifier.clip(ClayShapeChip).background(SubtleBg)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(chipMod)
                        .clickable { onSelect(lang) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            lang.nativeName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isSel) ElectricCyan else DarkSlateText
                        )
                        if (lang.nativeName != lang.displayName) {
                            Text(lang.displayName, fontSize = 9.sp, color = SlateText)
                        }
                    }
                }
            }
        }
    }
}

/** Compact globe pill that opens the [LanguageSettingsDialog]. */
@Composable
fun LanguagePill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val voice = LanguageConfig.voiceLanguage
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(CircleShape)
            .background(SubtleBg)
            .border(BorderStroke(1.dp, CardBorder), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Default.Language, "Language", tint = ElectricCyan, modifier = Modifier.size(16.dp))
        Text(voice.nativeName, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = DarkSlateText)
    }
}
