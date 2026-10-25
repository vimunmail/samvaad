package com.example.xgestalkble.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.Log

object AssetLoaderUtils {
    private const val TAG = "AssetLoaderUtils"
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Load a bitmap from the assets folder.
     */
    fun loadBitmapFromAssets(context: Context, path: String): Bitmap? {
        return try {
            context.assets.open(path).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image asset from path '$path': ${e.message}")
            null
        }
    }

    /**
     * Play an MP3 file from the assets folder.
     */
    fun playAudioFromAssets(context: Context, path: String) {
        try {
            stopAssetAudio()
            
            // Check if asset file exists before attempting to open it
            try {
                context.assets.open(path).close()
            } catch (e: Exception) {
                Log.e(TAG, "Asset audio file does not exist: $path")
                return
            }

            mediaPlayer = MediaPlayer().apply {
                val descriptor = context.assets.openFd(path)
                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                descriptor.close()
                prepare()
                start()
            }
            Log.d(TAG, "Playing audio asset: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio asset '$path': ${e.message}", e)
        }
    }

    /**
     * Stop and release the media player.
     */
    fun stopAssetAudio() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player: ${e.message}")
        }
    }
}
