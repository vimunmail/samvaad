package com.example.xgestalkble.utils

import java.util.Locale

/**
 * Shared helpers for interpreting gesture class labels.
 *
 * Rest poses may be trained under several names (REST, REST1, REST2, …) so the
 * wearer can record the neutral hand in different positions. Every one of them
 * means "idle" — never a sign to display, speak, or push into the chat.
 */
object GestureLabels {
    /** True for any idle/rest label — "REST", "REST1", "REST_2", "rest", … */
    fun isRest(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val norm = label.trim().uppercase(Locale.US)
        return norm.startsWith("REST") ||
               norm.startsWith("IDLE") ||
               norm.startsWith("STILL") ||
               norm == "HAND REST" ||
               norm == "HAND_REST"
    }

    /** Canonical idle label shown in the UI for any rest variant. */
    const val REST = "REST"
}
