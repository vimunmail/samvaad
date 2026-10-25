package com.example.xgestalkble.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.exp

class TcnClassifier(
    val classes: Array<String>,
    private val weights: Map<String, Any>
) {
    companion object {
        private const val TAG = "TcnClassifier"
        
        fun loadFromFile(file: File): TcnClassifier {
            val json = JSONObject(file.readText())
            val classesJson = json.getJSONArray("classes")
            val classes = Array(classesJson.length()) { classesJson.getString(it) }

            val weightsJson = json.getJSONObject("weights")
            val weightsMap = mutableMapOf<String, Any>()
            val keys = weightsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                weightsMap[key] = weightsJson.get(key)
            }
            val classifier = TcnClassifier(classes, weightsMap)

            // Preprocessing metadata (feature_version >= 3 models)
            classifier.featureVersion = json.optInt("feature_version", 1)
            // Temporal pooling: "mean" (global average, newer models) or "last"
            // (final-step, legacy default for backward compatibility).
            classifier.pooling = json.optString("pooling", "last")
            if (json.has("feature_mean") && json.has("feature_std")) {
                val meanArr = json.getJSONArray("feature_mean")
                val stdArr = json.getJSONArray("feature_std")
                classifier.featureMean = FloatArray(meanArr.length()) { meanArr.getDouble(it).toFloat() }
                classifier.featureStd = FloatArray(stdArr.length()) { stdArr.getDouble(it).toFloat() }
            }
            if (json.has("rest_baseline")) {
                val rb = json.getJSONArray("rest_baseline")
                classifier.restBaseline = FloatArray(rb.length()) { rb.getDouble(it).toFloat() }
            }
            return classifier
        }
        
        fun loadFromModelsDir(context: Context, modelName: String): TcnClassifier? {
            val dir = context.getExternalFilesDir("models") ?: return null
            val file = File(dir, modelName)
            if (!file.exists()) return null
            return try {
                loadFromFile(file)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load TCN model: ${e.message}", e)
                null
            }
        }
    }
    
    private val convWeights = mutableMapOf<String, Array<Array<FloatArray>>>()
    private val convBiases = mutableMapOf<String, FloatArray>()

    // Preprocessing metadata loaded from the model file
    var featureVersion: Int = 1
        private set
    /** Raw 21-ch REST mean for SessionCalibrator drift compensation; null = legacy. */
    var restBaseline: FloatArray? = null
        private set
    private var featureMean: FloatArray? = null
    private var featureStd: FloatArray? = null
    // "mean" = global average pooling over the whole window (current trainer),
    // "last" = final-step pooling (legacy models trained before the pooling fix).
    private var pooling: String = "last"

    /**
     * Apply the same preprocessing the model was trained with:
     * session-invariant transform (v3+) and z-score standardization.
     */
    private fun preprocess(sequence: List<FloatArray>): List<FloatArray> {
        var frames = sequence
        if (featureVersion >= 3) {
            frames = frames.map { FeatureTransform.apply(it) }
        }
        val mean = featureMean
        val std = featureStd
        if (mean != null && std != null) {
            frames = frames.map { FeatureTransform.standardize(it, mean, std) }
        }
        return frames
    }
    
    private fun get1DArray(key: String): FloatArray {
        val arrJson = (weights[key] as? org.json.JSONArray) ?: throw Exception("Key $key is not an array")
        return FloatArray(arrJson.length()) { arrJson.getDouble(it).toFloat() }
    }
    
    private fun get2DArray(key: String): Array<FloatArray> {
        val arrJson = (weights[key] as? org.json.JSONArray) ?: throw Exception("Key $key is not a 2D array")
        return Array(arrJson.length()) { i ->
            val subArr = arrJson.getJSONArray(i)
            FloatArray(subArr.length()) { j -> subArr.getDouble(j).toFloat() }
        }
    }
    
    private fun get3DArray(key: String): Array<Array<FloatArray>> {
        val arrJson = (weights[key] as? org.json.JSONArray) ?: throw Exception("Key $key is not a 3D array")
        return Array(arrJson.length()) { i ->
            val subArr = arrJson.getJSONArray(i)
            Array(subArr.length()) { j ->
                val subSubArr = subArr.getJSONArray(j)
                FloatArray(subSubArr.length()) { k -> subSubArr.getDouble(k).toFloat() }
            }
        }
    }
    
    private fun getConvWeights(name: String): Array<Array<FloatArray>> {
        return convWeights.getOrPut(name) { get3DArray(name) }
    }
    
    private fun getConvBias(name: String): FloatArray {
        return convBiases.getOrPut(name) { get1DArray(name) }
    }
    
    private fun getLinearWeights(name: String): Array<FloatArray> {
        return get2DArray(name)
    }
    
    private fun getLinearBias(name: String): FloatArray {
        return get1DArray(name)
    }

    /**
     * Run forward pass and return predicted class label.
     */
    fun predict(sequence: List<FloatArray>): String {
        if (sequence.isEmpty()) return ""
        val logits = forward(preprocess(sequence))
        var maxIdx = 0
        var maxVal = logits[0]
        for (i in 1 until logits.size) {
            if (logits[i] > maxVal) {
                maxVal = logits[i]
                maxIdx = i
            }
        }
        return classes[maxIdx]
    }

    /**
     * Run forward pass and return predicted class + confidence (softmax probability).
     */
    fun predictWithConfidence(sequence: List<FloatArray>): Pair<String, Float> {
        if (sequence.isEmpty()) return Pair("REST", 0.0f)
        val probs = predictProbabilities(sequence)
        var maxIdx = 0
        var maxVal = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > maxVal) {
                maxVal = probs[i]
                maxIdx = i
            }
        }
        return Pair(classes[maxIdx], maxVal)
    }

    /**
     * Run forward pass and return the full softmax probability distribution
     * over [classes]. Used for output smoothing during live prediction.
     */
    fun predictProbabilities(sequence: List<FloatArray>): FloatArray {
        if (sequence.isEmpty()) return FloatArray(classes.size)
        val logits = forward(preprocess(sequence))

        // Stabilized Softmax to prevent Infinity/NaN overflow
        val maxLogit = logits.maxOrNull() ?: 0.0f
        var sumExp = 0.0f
        val probs = FloatArray(logits.size)
        for (i in logits.indices) {
            probs[i] = exp((logits[i] - maxLogit).toDouble()).toFloat()
            sumExp += probs[i]
        }
        if (sumExp > 0f) {
            for (i in probs.indices) probs[i] /= sumExp
        }
        return probs
    }

    /**
     * Forward pass matching the TcnTrainer implementation:
     *   tcn.0 (dilation=1, in=num_features, out=32)
     *   tcn.1 (dilation=2, in=32, out=64)
     *   tcn.2 (dilation=4, in=64, out=64)
     *   pooling: final sequence step
     *   linear: 64 -> num_classes
     */
    private fun forward(sequence: List<FloatArray>): FloatArray {
        val seqLen = sequence.size // Expected 30
        val numFeatures = sequence[0].size

        // Input sequence transpose: (batch_size=1, sequence_length, features) -> (features, sequence_length)
        var x = Array(numFeatures) { f ->
            FloatArray(seqLen) { t -> sequence[t][f] }
        }

        // Block 0 input channel count must match what the model was trained
        // with — read it from the stored weight shape, never hardcode it.
        val block0InChannels = getConvWeights("tcn.0.conv1.conv.weight")[0].size

        // Block 0: Dilation = 1, channels = block0InChannels -> 32
        x = runTCNBlock(x, minOf(block0InChannels, numFeatures), 32, 1, "tcn.0")

        // Block 1: Dilation = 2, channels = 32 -> 64
        x = runTCNBlock(x, 32, 64, 2, "tcn.1")

        // Block 2: Dilation = 4, channels = 64 -> 64
        x = runTCNBlock(x, 64, 64, 4, "tcn.2")
        
        // Sequence pooling: global average over time (mean) for models trained
        // with the pooling fix; final-step pooling (last) for legacy models.
        val pooled = FloatArray(64)
        if (pooling == "mean") {
            for (c in 0 until 64) {
                var s = 0.0f
                for (t in 0 until seqLen) s += x[c][t]
                pooled[c] = s / seqLen
            }
        } else {
            for (c in 0 until 64) pooled[c] = x[c][seqLen - 1]
        }

        // Linear classification layer
        val linWeight = getLinearWeights("linear.weight")
        val linBias = getLinearBias("linear.bias")
        val numClasses = classes.size

        val logits = FloatArray(numClasses)
        for (c in 0 until numClasses) {
            var sum = linBias[c]
            for (i in 0 until 64) {
                sum += linWeight[c][i] * pooled[i]
            }
            logits[c] = sum
        }
        
        return logits
    }
    
    private fun runTCNBlock(
        input: Array<FloatArray>,
        inChannels: Int,
        outChannels: Int,
        dilation: Int,
        blockPrefix: String
    ): Array<FloatArray> {
        val seqLen = input[0].size
        
        // conv1: CausalConv1d (dilation = d)
        val conv1Out = runCausalConv1d(input, inChannels, outChannels, dilation, "$blockPrefix.conv1.conv")
        // relu1
        for (c in 0 until outChannels) {
            for (t in 0 until seqLen) {
                conv1Out[c][t] = max(0.0f, conv1Out[c][t])
            }
        }
        
        // conv2: CausalConv1d (dilation = d)
        val conv2Out = runCausalConv1d(conv1Out, outChannels, outChannels, dilation, "$blockPrefix.conv2.conv")
        // relu2
        for (c in 0 until outChannels) {
            for (t in 0 until seqLen) {
                conv2Out[c][t] = max(0.0f, conv2Out[c][t])
            }
        }
        
        // Downsample: Conv1d with kernel size 1
        val res = if (inChannels != outChannels) {
            runDownsample(input, inChannels, outChannels, "$blockPrefix.downsample")
        } else {
            input
        }
        
        // Residual addition + relu
        val blockOut = Array(outChannels) { FloatArray(seqLen) }
        for (c in 0 until outChannels) {
            for (t in 0 until seqLen) {
                blockOut[c][t] = max(0.0f, conv2Out[c][t] + res[c][t])
            }
        }
        
        return blockOut
    }
    
    /**
     * Run 1D Causal Convolution:
     *   Kernel size = 3, stride = 1.
     *   Padding is temporal on the left by (kernel_size - 1) * dilation.
     */
    private fun runCausalConv1d(
        input: Array<FloatArray>,
        inChannels: Int,
        outChannels: Int,
        dilation: Int,
        layerPath: String
    ): Array<FloatArray> {
        val seqLen = input[0].size
        val w = getConvWeights("$layerPath.weight")
        val b = getConvBias("$layerPath.bias")
        
        val output = Array(outChannels) { FloatArray(seqLen) }
        
        for (c in 0 until outChannels) {
            val biasVal = b[c]
            for (t in 0 until seqLen) {
                var sum = biasVal
                for (c_in in 0 until inChannels) {
                    val w0 = w[c][c_in][0]
                    val w1 = w[c][c_in][1]
                    val w2 = w[c][c_in][2]
                    
                    val v0 = if (t - 2 * dilation >= 0) input[c_in][t - 2 * dilation] else 0.0f
                    val v1 = if (t - dilation >= 0) input[c_in][t - dilation] else 0.0f
                    val v2 = input[c_in][t]
                    
                    sum += w0 * v0 + w1 * v1 + w2 * v2
                }
                output[c][t] = sum
            }
        }
        return output
    }
    
    /**
     * Run Conv1d Downsample:
     *   Kernel size = 1, stride = 1, dilation = 1.
     */
    private fun runDownsample(
        input: Array<FloatArray>,
        inChannels: Int,
        outChannels: Int,
        layerPath: String
    ): Array<FloatArray> {
        val seqLen = input[0].size
        val w = getConvWeights("$layerPath.weight") // shape: (outChannels, inChannels, 1)
        val b = getConvBias("$layerPath.bias")     // shape: (outChannels)
        
        val output = Array(outChannels) { FloatArray(seqLen) }
        
        for (c in 0 until outChannels) {
            val biasVal = b[c]
            for (t in 0 until seqLen) {
                var sum = biasVal
                for (c_in in 0 until inChannels) {
                    sum += w[c][c_in][0] * input[c_in][t]
                }
                output[c][t] = sum
            }
        }
        return output
    }
}
