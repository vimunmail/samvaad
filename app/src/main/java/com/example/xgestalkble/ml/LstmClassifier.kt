package com.example.xgestalkble.ml

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Inference for the single-layer LSTM gesture classifier trained by [LstmTrainer].
 *
 * Architecture (must mirror the trainer exactly):
 *   input  : (seq_len, num_features) standardized frames
 *   lstm   : one LSTM layer, hidden = [hiddenSize], gates packed [i, f, g, o]
 *   pooling: mean over every hidden state across time ("mean") — the same
 *            full-window pooling the TCN uses — or the final hidden state ("last")
 *   linear : hidden -> num_classes, then softmax
 *
 * Weights are stored as plain JSON arrays (same convention as TcnClassifier):
 *   lstm.weight_ih (4H x input), lstm.weight_hh (4H x H), lstm.bias (4H),
 *   linear.weight (classes x H), linear.bias (classes).
 */
class LstmClassifier(
    val classes: Array<String>,
    private val weightIh: Array<FloatArray>,   // (4H, input)
    private val weightHh: Array<FloatArray>,   // (4H, H)
    private val bias: FloatArray,              // (4H)
    private val linearW: Array<FloatArray>,    // (classes, H)
    private val linearB: FloatArray,           // (classes)
    val hiddenSize: Int
) {
    companion object {
        private const val TAG = "LstmClassifier"

        fun loadFromFile(file: File): LstmClassifier {
            val json = JSONObject(file.readText())
            val classesJson = json.getJSONArray("classes")
            val classes = Array(classesJson.length()) { classesJson.getString(it) }
            val weights = json.getJSONObject("weights")

            fun arr2d(key: String): Array<FloatArray> {
                val outer = weights.getJSONArray(key)
                return Array(outer.length()) { i ->
                    val row = outer.getJSONArray(i)
                    FloatArray(row.length()) { j -> row.getDouble(j).toFloat() }
                }
            }
            fun arr1d(key: String): FloatArray {
                val a = weights.getJSONArray(key)
                return FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }

            val weightHh = arr2d("lstm.weight_hh")
            val hidden = if (weightHh.isNotEmpty()) weightHh[0].size else json.optInt("hidden_size", 64)

            val clf = LstmClassifier(
                classes = classes,
                weightIh = arr2d("lstm.weight_ih"),
                weightHh = weightHh,
                bias = arr1d("lstm.bias"),
                linearW = arr2d("linear.weight"),
                linearB = arr1d("linear.bias"),
                hiddenSize = hidden
            )
            clf.featureVersion = json.optInt("feature_version", 1)
            clf.pooling = json.optString("pooling", "mean")
            if (json.has("feature_mean") && json.has("feature_std")) {
                val m = json.getJSONArray("feature_mean")
                val s = json.getJSONArray("feature_std")
                clf.featureMean = FloatArray(m.length()) { m.getDouble(it).toFloat() }
                clf.featureStd = FloatArray(s.length()) { s.getDouble(it).toFloat() }
            }
            if (json.has("rest_baseline")) {
                val rb = json.getJSONArray("rest_baseline")
                clf.restBaseline = FloatArray(rb.length()) { rb.getDouble(it).toFloat() }
            }
            return clf
        }

        fun loadFromModelsDir(context: Context, modelName: String): LstmClassifier? {
            val dir = context.getExternalFilesDir("models") ?: return null
            val file = File(dir, modelName)
            if (!file.exists()) return null
            return try {
                loadFromFile(file)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load LSTM model: ${e.message}", e)
                null
            }
        }
    }

    var featureVersion: Int = 1
        private set
    /** Raw 21-ch REST mean for SessionCalibrator drift compensation; null = legacy. */
    var restBaseline: FloatArray? = null
        private set
    private var featureMean: FloatArray? = null
    private var featureStd: FloatArray? = null
    private var pooling: String = "mean"

    /** Same preprocessing the model was trained with (transform + z-score). */
    private fun preprocess(sequence: List<FloatArray>): List<FloatArray> {
        var frames = sequence
        if (featureVersion >= 3) frames = frames.map { FeatureTransform.apply(it) }
        val mean = featureMean
        val std = featureStd
        if (mean != null && std != null) frames = frames.map { FeatureTransform.standardize(it, mean, std) }
        return frames
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    /** Run the LSTM forward and return raw logits over [classes]. */
    private fun forward(sequence: List<FloatArray>): FloatArray {
        val h = hiddenSize
        val seqLen = sequence.size
        val hState = FloatArray(h)
        val cState = FloatArray(h)
        // Accumulate hidden states for mean pooling.
        val pooled = FloatArray(h)

        for (t in 0 until seqLen) {
            val x = sequence[t]
            // gate pre-activations: bias + W_ih·x + W_hh·h
            for (k in 0 until 4 * h) {
                var s = bias[k]
                val wih = weightIh[k]
                for (j in x.indices) s += wih[j] * x[j]
                val whh = weightHh[k]
                for (j in 0 until h) s += whh[j] * hState[j]
                gateBuf[k] = s
            }
            for (n in 0 until h) {
                val i = sigmoid(gateBuf[n])
                val f = sigmoid(gateBuf[h + n])
                val g = tanh(gateBuf[2 * h + n].toDouble()).toFloat()
                val o = sigmoid(gateBuf[3 * h + n])
                val c = f * cState[n] + i * g
                cState[n] = c
                hState[n] = o * tanh(c.toDouble()).toFloat()
            }
            if (pooling == "mean") {
                for (n in 0 until h) pooled[n] += hState[n]
            }
        }

        val rep = if (pooling == "mean") {
            FloatArray(h) { pooled[it] / seqLen }
        } else {
            hState
        }

        val numClasses = classes.size
        val logits = FloatArray(numClasses)
        for (c in 0 until numClasses) {
            var sum = linearB[c]
            val w = linearW[c]
            for (i in 0 until h) sum += w[i] * rep[i]
            logits[c] = sum
        }
        return logits
    }

    // Reused scratch buffer for the 4H gate pre-activations (single-threaded use).
    private val gateBuf = FloatArray(4 * hiddenSize)

    fun predict(sequence: List<FloatArray>): String {
        if (sequence.isEmpty()) return ""
        val logits = forward(preprocess(sequence))
        var maxIdx = 0
        for (i in 1 until logits.size) if (logits[i] > logits[maxIdx]) maxIdx = i
        return classes[maxIdx]
    }

    fun predictWithConfidence(sequence: List<FloatArray>): Pair<String, Float> {
        if (sequence.isEmpty()) return Pair("REST", 0f)
        val probs = predictProbabilities(sequence)
        var maxIdx = 0
        for (i in 1 until probs.size) if (probs[i] > probs[maxIdx]) maxIdx = i
        return Pair(classes[maxIdx], probs[maxIdx])
    }

    /** Full softmax distribution — fed to GesturePredictionEngine for smoothing. */
    fun predictProbabilities(sequence: List<FloatArray>): FloatArray {
        if (sequence.isEmpty()) return FloatArray(classes.size)
        val logits = forward(preprocess(sequence))
        val maxLogit = logits.maxOrNull() ?: 0f
        var sumExp = 0f
        val probs = FloatArray(logits.size)
        for (i in logits.indices) {
            probs[i] = exp((logits[i] - maxLogit).toDouble()).toFloat()
            sumExp += probs[i]
        }
        if (sumExp > 0f) for (i in probs.indices) probs[i] /= sumExp
        return probs
    }
}
