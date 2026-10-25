package com.example.xgestalkble.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-wide language preference for the Conversation chat, persisted to
 * SharedPreferences so the user's choice survives restarts. Two independent
 * directions, because the deaf user and the hearing person may not share a
 * language:
 *
 *  - [voiceLanguage]  — sign → spoken voice + on-screen text (the wearer).
 *  - [sttLanguage]    — the hearing person's speech → text.
 *
 * Both Home and Chat read/write this single source of truth. Call [init] once
 * at startup (see MainActivity).
 */
object LanguageConfig {
    private const val PREFS_NAME = "AppLanguage"
    private const val KEY_VOICE = "voiceLanguage"
    private const val KEY_STT = "sttLanguage"
    private const val KEY_GENDER = "voiceGender"
    private const val KEY_TONE = "voiceTone"

    var voiceLanguage by mutableStateOf(ChatLanguage.ENGLISH)
        private set
    var sttLanguage by mutableStateOf(ChatLanguage.ENGLISH)
        private set

    /** Voice style for the spoken (sign → audio) voice: gender + modulation. */
    var voiceGender by mutableStateOf(TtsVoiceGender.DEFAULT)
        private set
    var voiceTone by mutableStateOf(TtsVoiceTone.NORMAL)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        voiceLanguage = decode(prefs.getString(KEY_VOICE, null))
        sttLanguage = decode(prefs.getString(KEY_STT, null))
        voiceGender = TtsVoiceGender.entries.firstOrNull { it.name == prefs.getString(KEY_GENDER, null) }
            ?: TtsVoiceGender.DEFAULT
        voiceTone = TtsVoiceTone.entries.firstOrNull { it.name == prefs.getString(KEY_TONE, null) }
            ?: TtsVoiceTone.NORMAL
        TextToSpeechHelper.setVoiceStyle(voiceGender, voiceTone)
        TextToSpeechHelper.setLanguage(voiceLanguage.locale)
    }

    fun setVoiceStyle(context: Context, gender: TtsVoiceGender, tone: TtsVoiceTone) {
        voiceGender = gender
        voiceTone = tone
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_GENDER, gender.name).putString(KEY_TONE, tone.name).apply()
        TextToSpeechHelper.setVoiceStyle(gender, tone)
    }

    fun setVoiceLanguage(context: Context, lang: ChatLanguage) {
        voiceLanguage = lang
        TextToSpeechHelper.setLanguage(lang.locale)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VOICE, lang.name).apply()
    }

    fun setSttLanguage(context: Context, lang: ChatLanguage) {
        sttLanguage = lang
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STT, lang.name).apply()
    }

    private fun decode(name: String?): ChatLanguage =
        ChatLanguage.entries.firstOrNull { it.name == name } ?: ChatLanguage.ENGLISH
}
