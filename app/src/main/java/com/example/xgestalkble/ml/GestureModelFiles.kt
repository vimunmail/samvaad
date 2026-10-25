package com.example.xgestalkble.ml

import com.example.xgestalkble.utils.RecordingModelType
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Finds saved gesture models from their JSON metadata.
 *
 * Model files are commonly imported or renamed, so their filename must never be
 * used to decide whether they are RFC, TCN, or LSTM models. Keeping this lookup
 * shared means every screen offers the same model files in the same order.
 */
object GestureModelFiles {
    fun findForType(directory: File?, type: RecordingModelType): List<String> {
        if (directory == null || !directory.isDirectory) return emptyList()

        return directory.listFiles { file ->
            file.isFile && (file.extension.equals("json", ignoreCase = true) ||
                file.extension.equals("lstm", ignoreCase = true))
        }
            ?.filter { modelType(it) == type }
            ?.sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
            ?.map { it.name }
            ?: emptyList()
    }

    /** Returns the architecture declared by a saved model, or null for invalid files. */
    fun modelType(file: File): RecordingModelType? = try {
        val json = JSONObject(file.readText())
        when {
            json.optString("model_type").equals("lstm", ignoreCase = true) ->
                RecordingModelType.LSTM
            json.optString("model_type").equals("tcn", ignoreCase = true) || json.has("weights") ->
                RecordingModelType.TCN
            json.has("trees") -> RecordingModelType.RFC
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
