package com.example.xgestalkble.utils

import java.util.Locale

/**
 * Languages the Conversation chat can speak / translate into.
 *
 * [code] is the key used inside `assets/translations/gesture_labels.json`.
 * [locale] drives both Text-to-Speech and on-device Speech-to-Text.
 *
 * Bundled and saved gesture meanings work offline. New English labels use
 * the paired computer's IndicTrans2 service or optional Google Cloud Translation. Speech uses
 * the device's installed TTS voices; translation and voice availability are separate.
 *
 * To add a language: add an entry here, then add its [code] translations to
 * `assets/translations/gesture_labels.json`. ENGLISH is the identity language —
 * labels are shown verbatim and never looked up.
 */
enum class ChatLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val locale: Locale
) {
    ENGLISH("en", "English", "English", Locale.US),
    HINDI("hi", "Hindi", "हिन्दी", Locale.forLanguageTag("hi-IN")),
    BENGALI("bn", "Bengali", "বাংলা", Locale.forLanguageTag("bn-IN")),
    TELUGU("te", "Telugu", "తెలుగు", Locale.forLanguageTag("te-IN")),
    MARATHI("mr", "Marathi", "मराठी", Locale.forLanguageTag("mr-IN")),
    TAMIL("ta", "Tamil", "தமிழ்", Locale.forLanguageTag("ta-IN")),
    URDU("ur", "Urdu", "اردو", Locale.forLanguageTag("ur-IN")),
    GUJARATI("gu", "Gujarati", "ગુજરાતી", Locale.forLanguageTag("gu-IN")),
    KANNADA("kn", "Kannada", "ಕನ್ನಡ", Locale.forLanguageTag("kn-IN")),
    MALAYALAM("ml", "Malayalam", "മലയാളം", Locale.forLanguageTag("ml-IN")),
    PUNJABI("pa", "Punjabi", "ਪੰਜਾਬੀ", Locale.forLanguageTag("pa-IN")),
    ODIA("or", "Odia", "ଓଡ଼ିଆ", Locale.forLanguageTag("or-IN")),
    ASSAMESE("as", "Assamese", "অসমীয়া", Locale.forLanguageTag("as-IN"));

    /** BCP-47 tag for SpeechRecognizer's EXTRA_LANGUAGE (e.g. "ml-IN"). */
    val bcp47: String
        get() = locale.toLanguageTag()

    /** True for the language whose labels need no dictionary lookup. */
    val isIdentity: Boolean get() = this == ENGLISH
}
