package com.example.xgestalkble.utils.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A local on-device Speech-to-Text recognizer utilizing OpenAI's Whisper model via TFLite.
 * Matches the public interface of SpeechToTextHelper so it can be swapped in easily.
 */
class WhisperSpeechRecognizer(
    private val context: Context,
    private val modelManager: WhisperModelManager
) {
    companion object {
        private const val TAG = "WhisperSpeechRecognizer"
    }

    private var interpreter: Interpreter? = null
    private val whisperUtil = WhisperUtil()
    private val audioRecorder = AudioRecorder()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInfo: ((String) -> Unit)? = null

    var languageTag: String? = null // BCP-47 language tag (e.g. "ml-IN", "en-US")
    private var active = false

    fun start() {
        if (active) return
        active = true
        onListeningChanged?.invoke(true)
        onInfo?.invoke("Starting local speech recognition...")

        scope.launch {
            try {
                if (interpreter == null) {
                    withContext(Dispatchers.IO) {
                        if (!modelManager.isReady()) {
                            throw IllegalStateException("Model files are missing or incomplete.")
                        }
                        val options = Interpreter.Options().apply {
                            setNumThreads(4)
                        }
                        interpreter = Interpreter(modelManager.modelFile, options)
                        val vocabLoaded = whisperUtil.loadFiltersAndVocab(true, modelManager.vocabFile.absolutePath)
                        if (!vocabLoaded) {
                            throw IllegalStateException("Failed to load vocabulary filters binary.")
                        }
                    }
                }

                audioRecorder.onUtterance = { samples ->
                    processAudioAsync(samples)
                }
                audioRecorder.onSpeechActive = { active ->
                    if (active) {
                        onPartial?.invoke("Transcribing...")
                    }
                }
                audioRecorder.onError = { err ->
                    onError?.invoke(err)
                    stop()
                }

                audioRecorder.start()
                withContext(Dispatchers.Main) {
                    onInfo?.invoke("Listening (100% offline)...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke("Offline STT error: ${e.localizedMessage}")
                    stop()
                }
            }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        audioRecorder.stop()
        onListeningChanged?.invoke(false)
    }

    fun destroy() {
        stop()
        interpreter?.close()
        interpreter = null
    }

    private fun processAudioAsync(samples: FloatArray) {
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    onPartial?.invoke("Processing speech...")
                }
                val text = transcribe(samples)
                withContext(Dispatchers.Main) {
                    if (text.trim().isNotEmpty()) {
                        onFinal?.invoke(text)
                    } else {
                        onPartial?.invoke("")
                        onInfo?.invoke("Could not understand. Try again.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke("Transcription failed: ${e.localizedMessage}")
                }
            }
        }
    }

    private suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        val currInterpreter = interpreter ?: throw IllegalStateException("Interpreter not initialized")

        val sampleRate = WhisperUtil.WHISPER_SAMPLE_RATE
        val chunkSize = WhisperUtil.WHISPER_CHUNK_SIZE
        val nSamples = sampleRate * chunkSize // 480000

        // Pad or truncate samples to exactly 480000
        val paddedSamples = if (samples.size >= nSamples) {
            samples.copyOfRange(0, nSamples)
        } else {
            FloatArray(nSamples).apply {
                System.arraycopy(samples, 0, this, 0, samples.size)
            }
        }

        // Get Mel Spectrogram
        val melData = whisperUtil.getMelSpectrogram(paddedSamples, nSamples, samples.size, 4)

        // Reshape melData [240000] to inputs [1, 80, 3000]
        val inputs = Array(1) { Array(80) { FloatArray(3000) } }
        for (j in 0 until 80) {
            System.arraycopy(melData, j * 3000, inputs[0][j], 0, 3000)
        }

        // Prepare outputs shape
        val outputTensor = currInterpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        val outputLen = outputShape[1]
        val outputs = Array(1) { IntArray(outputLen) }

        // Execute model
        currInterpreter.run(inputs, outputs)

        // Decode predicted token IDs
        val byteBuffer = ByteArrayOutputStream()
        for (token in outputs[0]) {
            if (token == whisperUtil.tokenEOT) {
                break
            }
            if (token < 50256) {
                val wordBytes = whisperUtil.getWordFromToken(token)
                if (wordBytes != null) {
                    byteBuffer.write(wordBytes)
                }
            }
        }

        val decodedText = byteBuffer.toString("UTF-8")
        cleanText(decodedText)
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("\\[_.*_\\]"), "") // strip remaining special tags
            .trim()
    }
}
