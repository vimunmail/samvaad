package com.example.xgestalkble.utils

import android.content.Context
import android.util.AtomicFile
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/** Bundled IndicTrans2 inference. No network, account, token or computer is used. */
internal object PhoneGestureTranslation {
    private val mutex = Mutex()
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private var engine: Engine? = null

    suspend fun translate(context: Context, texts: List<String>, language: ChatLanguage): List<String> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val active = engine ?: Engine(context.applicationContext).also { engine = it }
                texts.map { currentCoroutineContext().ensureActive(); active.translate(it, language.code) }
            }
        }

    private class Engine(private val context: Context) {
        private fun asset(name: String): File {
            val file = AtomicFile(File(context.filesDir, "indictrans2-int8-v1/$name"))
            val size = context.assets.openFd("indictrans2/$name").use { it.length }
            if (file.baseFile.length() != size) {
                file.baseFile.parentFile?.mkdirs()
                val stream = file.startWrite()
                try {
                    context.assets.open("indictrans2/$name").use { it.copyTo(stream) }
                    file.finishWrite(stream)
                } catch (error: Exception) { file.failWrite(stream); throw error }
            }
            return file.baseFile
        }
        private fun session(name: String, tokenizer: Boolean = false): OrtSession = OrtSession.SessionOptions().use {
            it.setIntraOpNumThreads(4)
            it.setInterOpNumThreads(1)
            it.setMemoryPatternOptimization(false)
            it.setCPUArenaAllocator(false)
            if (tokenizer) it.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            env.createSession(asset(name).absolutePath, it)
        }
        private val encoder = session("encoder.onnx")
        private val decoder = session("decoder.onnx")
        private val tokenizer = session("tokenizer.onnx", true)
        private val vocab = JSONObject(context.assets.open("indictrans2/vocab.json").bufferedReader().use { it.readText() })
        private val sourceMap = vocab.getJSONArray("sourceMap").let { a -> LongArray(a.length()) { a.getLong(it) } }
        private val pieces = vocab.getJSONArray("pieces").let { a -> Array(a.length()) { a.getString(it) } }
        private val processor = IndicEnglishProcessor(vocab.getJSONObject("processing"), vocab.getJSONObject("scripts"))

        private fun encode(text: String, language: String): LongArray {
            val inputs = linkedMapOf<String, OnnxTensor>()
            try {
                inputs["inputs"] = OnnxTensor.createTensor(env, arrayOf(text))
                // The native SentencePiece graph's remaining inputs have exported default initializers.
                tokenizer.run(inputs).use { result ->
                    val ids = result[0].value as IntArray
                    return longArrayOf(vocab.getLong("englishTag"), vocab.getJSONObject("languageTags").getLong(language)) +
                        ids.map { sourceMap[it] }.toLongArray() + longArrayOf(2)
                }
            } finally { inputs.values.forEach { it.close() } }
        }

        private data class Beam(val ids: LongArray, val score: Double)
        private data class Candidate(val beam: Int, val token: Int, val score: Double)

        suspend fun translate(text: String, language: String): String {
            val prepared = processor.prepare(text)
            val ids = encode(prepared.text, language)
            require(ids.size <= 256) { "This sentence is too long. Translate it in shorter sentences." }
            val mask = LongArray(ids.size) { 1 }
            val hidden: Array<FloatArray>
            OnnxTensor.createTensor(env, arrayOf(ids)).use { input ->
                OnnxTensor.createTensor(env, arrayOf(mask)).use { attention ->
                    encoder.run(mapOf("input_ids" to input, "attention_mask" to attention)).use {
                        @Suppress("UNCHECKED_CAST")
                        hidden = (it[0].value as Array<Array<FloatArray>>)[0]
                    }
                }
            }
            var beams = listOf(Beam(longArrayOf(2), 0.0))
            val finished = mutableListOf<Beam>()
            for (step in 0 until 128) {
                currentCoroutineContext().ensureActive()
                val logits: Array<FloatArray>
                OnnxTensor.createTensor(env, beams.map { it.ids }.toTypedArray()).use { prefix ->
                    OnnxTensor.createTensor(env, Array(beams.size) { hidden }).use { state ->
                        OnnxTensor.createTensor(env, Array(beams.size) { mask }).use { attention ->
                            decoder.run(mapOf("decoder_ids" to prefix, "encoder_hidden" to state, "attention_mask" to attention)).use {
                                @Suppress("UNCHECKED_CAST")
                                logits = it[0].value as Array<FloatArray>
                            }
                        }
                    }
                }
                // Five-beam whole-sentence decoding, with EOS hypotheses kept separately.
                val best = mutableListOf<Candidate>()
                logits.forEachIndexed { beamIndex, row ->
                    val max = row.max().toDouble()
                    val logZ = max + ln(row.sumOf { exp(it.toDouble() - max) })
                    row.forEachIndexed { token, value ->
                        if (token != 0 && token != 1 && token != 3) {
                            val score = beams[beamIndex].score + value - logZ
                            if (best.size < 10 || score > best.last().score) {
                                best.add(Candidate(beamIndex, token, score))
                                best.sortByDescending { it.score }
                                if (best.size > 10) best.removeAt(10)
                            }
                        }
                    }
                }
                val next = mutableListOf<Beam>()
                best.forEachIndexed { rank, candidate ->
                    val beam = Beam(beams[candidate.beam].ids + candidate.token.toLong(), candidate.score)
                    if (candidate.token == 2) {
                        if (rank < 5) finished.add(beam)
                    } else if (next.size < 5) next.add(beam)
                }
                if (finished.size >= 5 || next.isEmpty()) break
                beams = next
            }
            check(finished.isNotEmpty()) { "The sentence could not be completed. Try a shorter, complete English sentence." }
            val winner = finished.maxBy { it.score / (it.ids.size - 1).coerceAtLeast(1) }
            val raw = winner.ids.drop(1).filter { it > 3 }.joinToString("") { pieces[it.toInt()] }.replace('▁', ' ').trim()
            val result = processor.finish(raw, language, prepared.entities)
            check(result.isNotBlank() && !result.contains(Regex("<ctrl\\d+>|<unk>|<ID", RegexOption.IGNORE_CASE)) &&
                hasRegionalScript(result, language)) { "Please reword this sentence and translate again." }
            return result
        }
    }

    internal fun hasRegionalScript(text: String, language: String): Boolean {
        val range = when (language) {
            "hi", "mr" -> 0x900..0x97f; "bn", "as" -> 0x980..0x9ff
            "pa" -> 0xa00..0xa7f; "gu" -> 0xa80..0xaff; "or" -> 0xb00..0xb7f
            "ta" -> 0xb80..0xbff; "te" -> 0xc00..0xc7f; "kn" -> 0xc80..0xcff
            "ml" -> 0xd00..0xd7f; "ur" -> 0x600..0x6ff; else -> return false
        }
        return text.none(Char::isLetter) || text.any { it.code in range }
    }
}
