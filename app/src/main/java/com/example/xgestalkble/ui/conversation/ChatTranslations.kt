package com.example.xgestalkble.ui.conversation

import android.content.Context
import com.example.xgestalkble.utils.ChatLanguage
import com.example.xgestalkble.utils.GestureTranslator

/** Exact bundled phrases only. Unknown sentences retain their original wording. */
internal object ChatTranslations {
    @Synchronized
    fun translate(context: Context, text: String, language: ChatLanguage): String? {
        GestureTranslator.ensureLoaded(context)
        return GestureTranslator.translate(text, language).takeIf { !it.equals(text, ignoreCase = true) }
    }

    /** Share only prepared meanings; a guest's language need not match the wearer's. */
    fun forGuests(context: Context, label: String): Map<String, String> {
        GestureTranslator.ensureLoaded(context)
        return ChatLanguage.entries.asSequence().filterNot { it.isIdentity }.mapNotNull { language ->
            GestureTranslator.savedTranslation(label, language)?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 300 }
                ?.let { language.code to it }
        }.toMap()
    }
}
