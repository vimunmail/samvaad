package com.example.xgestalkble

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data object Tools : NavKey
@Serializable data object SerialMonitor : NavKey
@Serializable data object DataCollection : NavKey
@Serializable data object TeachGestures : NavKey
@Serializable data object Prediction : NavKey
@Serializable data object GestureToVoice : NavKey
@Serializable data object Conversation : NavKey
@Serializable data object SoundAlert : NavKey
@Serializable data object TextToAudio : NavKey
@Serializable data object ModelTranslations : NavKey

