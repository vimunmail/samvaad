package com.example.xgestalkble.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Ships trained gesture models AND recorded datasets inside the APK so a
 * freshly installed app is usable immediately — share the APK and the
 * receiver has the same models + training data, no separate file transfer
 * into Android/data (which modern Android blocks for other apps anyway).
 *
 * On startup every file under `assets/models/` is copied into the external
 * `models/` directory and every file under `assets/datasets/` into
 * `TelemetryData/`. Existing files are NEVER overwritten, so anything the
 * user retrained or re-recorded on this phone always wins over the bundle.
 *
 * To update a bundled copy after retraining/re-recording: pull the file from
 * `Android/data/<pkg>/files/` into the matching assets folder and rebuild.
 */
object BundledModels {
    private const val TAG = "BundledModels"

    fun seed(context: Context) {
        seedDir(context, assetDir = "models", targetDirName = "models")
        seedDir(context, assetDir = "datasets", targetDirName = "TelemetryData")
    }

    private fun seedDir(context: Context, assetDir: String, targetDirName: String) {
        try {
            val names = context.assets.list(assetDir) ?: return
            if (names.isEmpty()) return
            val targetDir = context.getExternalFilesDir(targetDirName) ?: return
            if (!targetDir.exists()) targetDir.mkdirs()
            for (name in names) {
                val target = File(targetDir, name)
                if (target.exists()) continue // user's (possibly newer) copy wins
                context.assets.open("$assetDir/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Log.d(TAG, "Seeded bundled $assetDir/$name (${target.length()} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed bundled $assetDir: ${e.message}")
        }
    }
}
