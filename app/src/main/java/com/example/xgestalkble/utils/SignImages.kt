package com.example.xgestalkble.utils

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Locale

/**
 * Resolves a gesture label to a sign picture bundled under the assets images
 * folder and decodes it for the Conversation chat (sign to image bubble).
 *
 * Digits 0-9 map to the numbers folder (the digit_rfc model). Word labels match
 * a `<LABEL>.png` (case / whitespace tolerant) across the sign folders. Pass the
 * model's own category as preferredFolder so e.g. the "emergency" model shows the
 * emergency-folder picture even when the same label also exists elsewhere. Any
 * label with no picture returns null and the chat shows text only.
 */
object SignImages {
    private const val BASE = "images"
    private val FOLDERS = listOf(
        "numbers", "emergency", "public-signs",
        "color-signs", "educational-signs", "emotional", "alphabets", "Hospital"
    )

    // folder -> (normalized label -> actual asset path)
    private val cache = HashMap<String, Map<String, String>>()

    /** Asset path of the picture for [label], or null if none is bundled. */
    fun resolve(context: Context, label: String, preferredFolder: String? = null): String? {
        val key = norm(label)
        if (key.isEmpty()) return null

        // Try an exact normalized match first, then a space-insensitive one so
        // e.g. "I am alone" still matches an "IAM ALONE.png" file.
        fun look(folder: String): String? {
            val idx = folderIndex(context, folder)
            return idx[key] ?: idx[key.replace(" ", "")]
        }

        // Digits 0-9 → numbers folder (the digit_rfc model).
        if (key.length == 1 && key[0].isDigit()) {
            look("numbers")?.let { return it }
        }
        // The model's own folder wins (e.g. the "emergency" model → emergency/).
        preferredFolder?.let { pf ->
            val folderMatch = FOLDERS.firstOrNull { it.equals(pf, ignoreCase = true) }
            if (folderMatch != null) {
                look(folderMatch)?.let { return it }
            }
        }
        // Otherwise the first folder that has a matching picture.
        for (folder in FOLDERS) {
            look(folder)?.let { return it }
        }
        return null
    }

    /** Decode an asset image, downsampled to ~[reqPx] on its long edge. */
    fun loadImageBitmap(context: Context, path: String, reqPx: Int = 420): ImageBitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > reqPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.assets.open(path).use {
                BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Build (and cache) a case/whitespace-normalized index of a folder's PNGs. */
    @Synchronized
    private fun folderIndex(context: Context, folder: String): Map<String, String> {
        cache[folder]?.let { return it }
        val map = HashMap<String, String>()
        try {
            val files = context.assets.list("$BASE/$folder") ?: emptyArray()
            for (f in files) {
                if (!f.endsWith(".png", ignoreCase = true)) continue
                val base = f.substringBeforeLast('.')
                if (base.equals("default", ignoreCase = true)) continue
                val nk = norm(base)
                val path = "$BASE/$folder/$f"
                map[nk] = path
                map.putIfAbsent(nk.replace(" ", ""), path) // space-insensitive alias

            }
        } catch (e: Exception) {
            // folder missing — leave the index empty
        }
        cache[folder] = map
        return map
    }

    /** Uppercase, trim, collapse internal whitespace, treat _ and - as spaces. */
    private fun norm(s: String): String =
        s.trim().uppercase(Locale.US)
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
}
