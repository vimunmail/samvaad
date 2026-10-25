package com.example.xgestalkble.ml

import android.content.Context
import android.util.Log
import com.example.xgestalkble.utils.GestureLabels
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

class TcnTrainer(
    val classes: Array<String>
) {
    companion object {
        private const val TAG = "TcnTrainer"
        private const val NUM_FEATURES = 21  // v3.1 firmware: 21-feature vector
        private const val SEQ_LEN = 30
        
        /**
         * Helper to split sequences and labels into train and test sets proportionally.
         */
        fun trainTestSplit(
            sequences: List<Array<FloatArray>>,
            labels: List<String>,
            testRatio: Float = 0.2f,
            seed: Long = 42L
        ): Pair<Pair<List<Array<FloatArray>>, List<String>>, Pair<List<Array<FloatArray>>, List<String>>> {
            val random = java.util.Random(seed)
            val classes = labels.toSet().sorted()
            
            val trainX = mutableListOf<Array<FloatArray>>()
            val trainY = mutableListOf<String>()
            val testX = mutableListOf<Array<FloatArray>>()
            val testY = mutableListOf<String>()
            
            for (cls in classes) {
                val indices = labels.indices.filter { labels[it] == cls }.toMutableList()
                indices.shuffle(random)
                
                val testCount = (indices.size * testRatio).toInt().coerceAtLeast(1)
                val testIndices = indices.take(testCount).toSet()
                
                for (idx in indices) {
                    if (idx in testIndices) {
                        testX.add(sequences[idx])
                        testY.add(labels[idx])
                    } else {
                        trainX.add(sequences[idx])
                        trainY.add(labels[idx])
                    }
                }
            }
            
            return Pair(Pair(trainX, trainY), Pair(testX, testY))
        }

        private fun isStationarySequence(seq: Array<FloatArray>): Boolean {
            // Accel channels 0..17: max - min <= 1.01f (allows at most 1 quantization level toggle)
            for (f in 0 until 18) {
                var minVal = Float.MAX_VALUE
                var maxVal = -Float.MAX_VALUE
                for (t in seq.indices) {
                    val v = seq[t][f]
                    if (v < minVal) minVal = v
                    if (v > maxVal) maxVal = v
                }
                if (maxVal - minVal > 1.01f) return false
            }
            // Gyro channels 18..20: rotation rate magnitude <= 40.0 deg/s for every
            // frame. Matches the gyroDeadband in GesturePredictionEngine (CSV analysis
            // shows REST gyro noise peaks at ±35 dps).
            for (t in seq.indices) {
                val gx = seq[t][18]
                val gy = seq[t][19]
                val gz = seq[t][20]
                val mag = sqrt(gx * gx + gy * gy + gz * gz)
                if (mag > 40.0f) return false
            }
            return true
        }

        /**
         * Groups contiguous rows of the same label, sliding a window of seqLen
         * to extract 3D sequence arrays of shape (num_samples, seqLen, features).
         * Matches webapp create_tcn_sequences logic.
         */
        fun createTcnSequences(
            X: Array<FloatArray>,
            y: Array<String>,
            seqLen: Int = 30,
            sessionIds: Array<String>? = null
        ): Pair<List<Array<FloatArray>>, List<String>> {
            val sequences = mutableListOf<Array<FloatArray>>()
            val labels = mutableListOf<String>()
            
            val totalRows = X.size
            if (totalRows < seqLen) return Pair(emptyList(), emptyList())

            val stride = when {
                totalRows > 30000 -> 6
                totalRows > 15000 -> 4
                totalRows > 5000 -> 2
                else -> 1
            }
            
            for (block in RecordingSessionSplitter.blocks(y, sessionIds)) {
                val blockSize = block.endExclusive - block.start
                if (blockSize < seqLen) continue
                for (s in 0..blockSize - seqLen step stride) {
                    val seq = Array(seqLen) { t -> X[block.start + s + t].copyOf() }
                    sequences.add(seq)
                    labels.add(block.label)
                }
            }
            
            return Pair(sequences, labels)
        }

        /**
         * Leakage-free sliding-window extraction + split.
         *
         * The old flow (createTcnSequences -> random trainTestSplit) shuffled
         * heavily-overlapping windows, so near-identical windows landed in both
         * train and validation. That made the reported validation accuracy far
         * too optimistic and hid overfitting.
         *
         * Here each contiguous same-label block is cut temporally: the first
         * (1 - testRatio) of the block feeds train windows, the remainder feeds
         * validation windows, and no window straddles the cut — train and val
         * never share a single frame. Every class still appears on both sides.
         */
        fun createTcnSequencesSplit(
            X: Array<FloatArray>,
            y: Array<String>,
            seqLen: Int = 30,
            testRatio: Float = 0.2f,
            sessionIds: Array<String>? = null
        ): Pair<Pair<List<Array<FloatArray>>, List<String>>, Pair<List<Array<FloatArray>>, List<String>>> {
            val trainX = mutableListOf<Array<FloatArray>>()
            val trainY = mutableListOf<String>()
            val valX = mutableListOf<Array<FloatArray>>()
            val valY = mutableListOf<String>()

            val totalRows = X.size
            if (totalRows < seqLen) {
                return Pair(Pair(trainX, trainY), Pair(valX, valY))
            }

            val stride = when {
                totalRows > 30000 -> 6
                totalRows > 15000 -> 4
                totalRows > 5000 -> 2
                else -> 1
            }

            fun emitBlock(block: RecordingSessionSplitter.Block, validationOnly: Boolean?) {
                val blockSize = block.endExclusive - block.start
                if (blockSize < seqLen) return
                // Frame index that divides train (before) from val (at/after).
                val cut = (blockSize * (1f - testRatio)).toInt().coerceIn(seqLen, blockSize)
                for (s in 0..blockSize - seqLen step stride) {
                    val seq = Array(seqLen) { t -> X[block.start + s + t].copyOf() }
                    when (validationOnly) {
                    false -> {
                        trainX.add(seq); trainY.add(block.label)
                    }
                    true -> {
                        valX.add(seq); valY.add(block.label)
                    }
                    null -> if (s + seqLen <= cut) {
                        // Window lies entirely in the train region.
                        trainX.add(seq); trainY.add(block.label)
                    } else if (s >= cut) {
                        // Window lies entirely in the val region (no overlap with cut).
                        valX.add(seq); valY.add(block.label)
                    }
                    }
                    // Windows straddling the cut are dropped to guarantee zero leakage.
                }
            }

            val blocks = RecordingSessionSplitter.blocks(y, sessionIds)
            val validationSessions = RecordingSessionSplitter.validationSessions(blocks, testRatio)
            val labelsWithSessionHoldout = validationSessions.map { it.first }.toSet()
            for (block in blocks) {
                val validationOnly = if (block.label in labelsWithSessionHoldout) {
                    (block.label to block.sessionId) in validationSessions
                } else {
                    null // Legacy or single-session label: retain temporal split.
                }
                emitBlock(block, validationOnly)
            }

            return Pair(Pair(trainX, trainY), Pair(valX, valY))
        }
    }

    // Model weight dictionary
    val weights = mutableMapOf<String, Any>()

    // Feature standardization stats (computed from the training set in fit(),
    // saved with the model so inference preprocesses identically)
    private var featureMean = FloatArray(NUM_FEATURES)
    private var featureStd = FloatArray(NUM_FEATURES) { 1f }

    // Per-channel mean of REST frames, saved as `rest_baseline`. At inference the
    // SessionCalibrator re-anchors each live power session to this, which is what
    // makes predictions survive a glove power-cycle. Null ⇒ no REST class ⇒
    // compensation disabled (same as a legacy model).
    private var restBaseline: FloatArray? = null
    
    // Gradient dictionary
    private val gradients = mutableMapOf<String, Any>()
    
    // Adam optimizer moments
    private val m = mutableMapOf<String, Any>()
    private val v = mutableMapOf<String, Any>()
    private var t = 0

    /**
     * Best validation accuracy reached during [fit]. The weights saved to disk
     * are the ones from this epoch (best-checkpoint restore), not the final
     * epoch — so a model that later overfits is never shipped.
     */
    var bestValAccuracy: Float = 0f
        private set

    init {
        val random = java.util.Random(42)
        val numClasses = classes.size

        // Block 0: NUM_FEATURES -> 32 channels (v3.1 firmware: 21 input features)
        weights["tcn.0.conv1.conv.weight"] = initWeights(32, NUM_FEATURES, 3, random)
        weights["tcn.0.conv1.conv.bias"] = initBias(32)
        weights["tcn.0.conv2.conv.weight"] = initWeights(32, 32, 3, random)
        weights["tcn.0.conv2.conv.bias"] = initBias(32)
        weights["tcn.0.downsample.weight"] = initWeights(32, NUM_FEATURES, 1, random)
        weights["tcn.0.downsample.bias"] = initBias(32)

        // Block 1: 32 -> 64 channels
        weights["tcn.1.conv1.conv.weight"] = initWeights(64, 32, 3, random)
        weights["tcn.1.conv1.conv.bias"] = initBias(64)
        weights["tcn.1.conv2.conv.weight"] = initWeights(64, 64, 3, random)
        weights["tcn.1.conv2.conv.bias"] = initBias(64)
        weights["tcn.1.downsample.weight"] = initWeights(64, 32, 1, random)
        weights["tcn.1.downsample.bias"] = initBias(64)

        // Block 2: 64 -> 64 channels (no downsample since channels are same)
        weights["tcn.2.conv1.conv.weight"] = initWeights(64, 64, 3, random)
        weights["tcn.2.conv1.conv.bias"] = initBias(64)
        weights["tcn.2.conv2.conv.weight"] = initWeights(64, 64, 3, random)
        weights["tcn.2.conv2.conv.bias"] = initBias(64)

        // Linear layer: 64 -> numClasses
        weights["linear.weight"] = initLinearWeights(numClasses, 64, random)
        weights["linear.bias"] = initBias(numClasses)

        // Initialize empty gradient and Adam states matching weights
        for ((k, w) in weights) {
            when (w) {
                is Array<*> -> {
                    if (w[0] is FloatArray) {
                        val w2d = w as Array<FloatArray>
                        gradients[k] = Array(w2d.size) { FloatArray(w2d[0].size) }
                        m[k] = Array(w2d.size) { FloatArray(w2d[0].size) }
                        v[k] = Array(w2d.size) { FloatArray(w2d[0].size) }
                    } else {
                        val w3d = w as Array<Array<FloatArray>>
                        gradients[k] = Array(w3d.size) { Array(w3d[0].size) { FloatArray(w3d[0][0].size) } }
                        m[k] = Array(w3d.size) { Array(w3d[0].size) { FloatArray(w3d[0][0].size) } }
                        v[k] = Array(w3d.size) { Array(w3d[0].size) { FloatArray(w3d[0][0].size) } }
                    }
                }
                is FloatArray -> {
                    gradients[k] = FloatArray(w.size)
                    m[k] = FloatArray(w.size)
                    v[k] = FloatArray(w.size)
                }
            }
        }
    }

    private fun initWeights(outChan: Int, inChan: Int, kSize: Int, random: java.util.Random): Array<Array<FloatArray>> {
        val stdDev = sqrt(2.0 / (inChan * kSize)).toFloat()
        return Array(outChan) {
            Array(inChan) {
                FloatArray(kSize) { (random.nextGaussian().toFloat() * stdDev) }
            }
        }
    }

    private fun initBias(outChan: Int): FloatArray {
        return FloatArray(outChan) { 0.0f }
    }

    private fun initLinearWeights(outFeat: Int, inFeat: Int, random: java.util.Random): Array<FloatArray> {
        val stdDev = sqrt(2.0 / inFeat).toFloat()
        return Array(outFeat) {
            FloatArray(inFeat) { (random.nextGaussian().toFloat() * stdDev) }
        }
    }

    // Forward pass intermediate states
    class CausalConv1dState(val x: Array<FloatArray>, val y: Array<FloatArray>)
    class DownsampleState(val x: Array<FloatArray>, val y: Array<FloatArray>)
    
    class TCNBlockState(
        val conv1: CausalConv1dState,
        val relu1: Array<FloatArray>,
        val conv2: CausalConv1dState,
        val relu2: Array<FloatArray>,
        val ds: DownsampleState?,
        val outBeforeRelu: Array<FloatArray>,
        val out: Array<FloatArray>
    )

    class TCNModelState(
        val block0: TCNBlockState,
        val block1: TCNBlockState,
        val block2: TCNBlockState,
        val lastStep: FloatArray,
        val logits: FloatArray
    )

    // Forward propagation execution
    fun forward(sequence: Array<FloatArray>, classesCount: Int): TCNModelState {
        val seqLen = sequence.size
        val numFeatures = sequence[0].size
        
        // Input transpose: (seqLen, numFeatures) -> (numFeatures, seqLen)
        val x = Array(numFeatures) { f ->
            FloatArray(seqLen) { t -> sequence[t][f] }
        }
        
        // TCN Block 0: Dilation = 1, channels = NUM_FEATURES -> 32
        val block0 = forwardBlock(x, numFeatures, 32, 1, "tcn.0")
        
        // TCN Block 1: Dilation = 2, channels = 32 -> 64
        val block1 = forwardBlock(block0.out, 32, 64, 2, "tcn.1")
        
        // TCN Block 2: Dilation = 4, channels = 64 -> 64
        val block2 = forwardBlock(block1.out, 64, 64, 4, "tcn.2")
        
        // Sequence pooling: global average over the whole window. Uses every
        // timestep instead of just the last one, so the classifier sees the full
        // temporal evolution of the gesture (the single biggest accuracy fix).
        val lastStep = FloatArray(64) { c ->
            var s = 0.0f
            for (t in 0 until seqLen) s += block2.out[c][t]
            s / seqLen
        }
        
        // Linear Dense projection
        val linearW = weights["linear.weight"] as Array<FloatArray>
        val linearB = weights["linear.bias"] as FloatArray
        val logits = FloatArray(classesCount)
        for (c in 0 until classesCount) {
            var sum = linearB[c]
            for (i in 0 until 64) {
                sum += linearW[c][i] * lastStep[i]
            }
            logits[c] = sum
        }
        
        return TCNModelState(block0, block1, block2, lastStep, logits)
    }

    private fun forwardBlock(
        input: Array<FloatArray>,
        inChannels: Int,
        outChannels: Int,
        dilation: Int,
        blockPrefix: String
    ): TCNBlockState {
        val seqLen = input[0].size
        
        val conv1State = forwardCausalConv1d(input, inChannels, outChannels, dilation, "$blockPrefix.conv1.conv")
        
        val relu1 = Array(outChannels) { FloatArray(seqLen) }
        for (c in 0 until outChannels) {
            for (t in 0 until seqLen) {
                relu1[c][t] = maxOf(0.0f, conv1State.y[c][t])
            }
        }
        
        val conv2State = forwardCausalConv1d(relu1, outChannels, outChannels, dilation, "$blockPrefix.conv2.conv")
        
        val relu2 = Array(outChannels) { FloatArray(seqLen) }
        for (c in 0 until outChannels) {
            for (t in 0 until seqLen) {
                relu2[c][t] = maxOf(0.0f, conv2State.y[c][t])
            }
        }
        
        val dsState = if (inChannels != outChannels) {
            forwardDownsample(input, inChannels, outChannels, "$blockPrefix.downsample")
        } else {
            null
        }
        
        val outBeforeRelu = Array(outChannels) { FloatArray(seqLen) }
        val out = Array(outChannels) { FloatArray(seqLen) }
        for (c in 0 until outChannels) {
            for (t in 0 until seqLen) {
                val resVal = dsState?.y?.get(c)?.get(t) ?: input[c][t]
                outBeforeRelu[c][t] = relu2[c][t] + resVal
                out[c][t] = maxOf(0.0f, outBeforeRelu[c][t])
            }
        }
        
        return TCNBlockState(conv1State, relu1, conv2State, relu2, dsState, outBeforeRelu, out)
    }

    private fun forwardCausalConv1d(
        input: Array<FloatArray>,
        inChannels: Int,
        outChannels: Int,
        dilation: Int,
        layerPath: String
    ): CausalConv1dState {
        val seqLen = input[0].size
        val w = weights["$layerPath.weight"] as Array<Array<FloatArray>>
        val b = weights["$layerPath.bias"] as FloatArray
        
        val output = Array(outChannels) { FloatArray(seqLen) }
        for (c in 0 until outChannels) {
            val biasVal = b[c]
            val w_c = w[c]
            for (t in 0 until seqLen) {
                var sum = biasVal
                for (c_in in 0 until inChannels) {
                    val w_c_in = w_c[c_in]
                    val w0 = w_c_in[0]
                    val w1 = w_c_in[1]
                    val w2 = w_c_in[2]
                    
                    val v0 = if (t - 2 * dilation >= 0) input[c_in][t - 2 * dilation] else 0.0f
                    val v1 = if (t - dilation >= 0) input[c_in][t - dilation] else 0.0f
                    val v2 = input[c_in][t]
                    
                    sum += w0 * v0 + w1 * v1 + w2 * v2
                }
                output[c][t] = sum
            }
        }
        return CausalConv1dState(input, output)
    }

    private fun forwardDownsample(
        input: Array<FloatArray>,
        inChannels: Int,
        outChannels: Int,
        layerPath: String
    ): DownsampleState {
        val seqLen = input[0].size
        val w = weights["$layerPath.weight"] as Array<Array<FloatArray>>
        val b = weights["$layerPath.bias"] as FloatArray
        
        val output = Array(outChannels) { FloatArray(seqLen) }
        for (c in 0 until outChannels) {
            val biasVal = b[c]
            val w_c = w[c]
            for (t in 0 until seqLen) {
                var sum = biasVal
                for (c_in in 0 until inChannels) {
                    val w_c_in = w_c[c_in]
                    sum += w_c_in[0] * input[c_in][t]
                }
                output[c][t] = sum
            }
        }
        return DownsampleState(input, output)
    }

    // Backpropagation execution. Writes into [grads] (a per-worker map) so many
    // samples can be backpropped concurrently; the forward state and weights are
    // only read. [sampleWeight] scales this sample's gradient so rare classes
    // count as much as frequent ones (class-balanced training).
    fun backward(grads: MutableMap<String, Any>, state: TCNModelState, targetIdx: Int, classesCount: Int, sampleWeight: Float = 1f) {
        val seqLen = SEQ_LEN

        // 1. Softmax + Cross Entropy Loss Derivative
        val maxLogit = state.logits.maxOrNull() ?: 0.0f
        var sumExp = 0.0f
        val probs = FloatArray(classesCount)
        for (c in 0 until classesCount) {
            probs[c] = exp((state.logits[c] - maxLogit).toDouble()).toFloat()
            sumExp += probs[c]
        }
        for (c in 0 until classesCount) {
            probs[c] = if (sumExp > 0f) probs[c] / sumExp else 0.0f
        }

        val dLogits = FloatArray(classesCount)
        for (c in 0 until classesCount) {
            dLogits[c] = sampleWeight * (probs[c] - (if (c == targetIdx) 1.0f else 0.0f))
        }
        
        // 2. Linear backward
        val linearW = weights["linear.weight"] as Array<FloatArray>
        val dLinearW = Array(classesCount) { FloatArray(64) }
        val dLinearB = FloatArray(classesCount)
        val dLastStep = FloatArray(64)
        
        for (c in 0 until classesCount) {
            dLinearB[c] = dLogits[c]
            for (i in 0 until 64) {
                dLinearW[c][i] = dLogits[c] * state.lastStep[i]
                dLastStep[i] += dLogits[c] * linearW[c][i]
            }
        }
        
        accumulateGrad2D(grads, "linear.weight", dLinearW)
        accumulateGrad1D(grads, "linear.bias", dLinearB)

        // 3. Sequence pooling backward — global average pooling spreads the
        // gradient evenly across every timestep (must mirror the mean in forward()).
        val dBlock2Out = Array(64) { FloatArray(seqLen) }
        for (c in 0 until 64) {
            val g = dLastStep[c] / seqLen
            for (t in 0 until seqLen) dBlock2Out[c][t] = g
        }

        // 4. Block 2 backward
        val dBlock1Out = backwardBlock(grads, dBlock2Out, state.block2, 64, 64, 4, "tcn.2")

        // 5. Block 1 backward
        val dBlock0Out = backwardBlock(grads, dBlock1Out, state.block1, 32, 64, 2, "tcn.1")

        // 6. Block 0 backward
        val dInput = backwardBlock(grads, dBlock0Out, state.block0, NUM_FEATURES, 32, 1, "tcn.0")
    }

    private fun backwardBlock(
        grads: MutableMap<String, Any>,
        dOut: Array<FloatArray>,
        blockState: TCNBlockState,
        inChannels: Int,
        outChannels: Int,
        dilation: Int,
        blockPrefix: String
    ): Array<FloatArray> {
        val seqLen = dOut[0].size
        
        val dRelu2 = Array(outChannels) { FloatArray(seqLen) }
        val dRes = Array(outChannels) { FloatArray(seqLen) }
        for (c in 0 until outChannels) {
            for (t in 0 until seqLen) {
                val grad = if (blockState.outBeforeRelu[c][t] > 0f) dOut[c][t] else 0f
                dRelu2[c][t] = grad
                dRes[c][t] = grad
            }
        }
        
        val dConv2Out = Array(outChannels) { FloatArray(seqLen) }
        for (c in 0 until outChannels) {
            for (t in 0 until seqLen) {
                dConv2Out[c][t] = if (blockState.relu2[c][t] > 0f) dRelu2[c][t] else 0f
            }
        }
        
        val dRelu1 = backwardCausalConv1d(grads, dConv2Out, blockState.conv2, outChannels, outChannels, dilation, "$blockPrefix.conv2.conv")
        
        val dConv1Out = Array(outChannels) { FloatArray(seqLen) }
        for (c in 0 until outChannels) {
            for (t in 0 until seqLen) {
                dConv1Out[c][t] = if (blockState.relu1[c][t] > 0f) dRelu1[c][t] else 0f
            }
        }
        
        val dConv1Input = backwardCausalConv1d(grads, dConv1Out, blockState.conv1, inChannels, outChannels, dilation, "$blockPrefix.conv1.conv")

        val dInput = Array(inChannels) { FloatArray(seqLen) }
        if (blockState.ds != null) {
            val dDsInput = backwardDownsample(grads, dRes, blockState.ds, inChannels, outChannels, "$blockPrefix.downsample")
            for (c in 0 until inChannels) {
                for (t in 0 until seqLen) {
                    dInput[c][t] = dConv1Input[c][t] + dDsInput[c][t]
                }
            }
        } else {
            for (c in 0 until inChannels) {
                for (t in 0 until seqLen) {
                    dInput[c][t] = dConv1Input[c][t] + dRes[c][t]
                }
            }
        }
        
        return dInput
    }

    private fun backwardCausalConv1d(
        grads: MutableMap<String, Any>,
        dOut: Array<FloatArray>,
        convState: CausalConv1dState,
        inChannels: Int,
        outChannels: Int,
        dilation: Int,
        layerPath: String
    ): Array<FloatArray> {
        val seqLen = dOut[0].size
        val w = weights["$layerPath.weight"] as Array<Array<FloatArray>>
        
        val dw = Array(outChannels) { Array(inChannels) { FloatArray(3) } }
        val db = FloatArray(outChannels)
        val dInput = Array(inChannels) { FloatArray(seqLen) }
        
        for (c in 0 until outChannels) {
            val w_c = w[c]
            val dw_c = dw[c]
            for (t in 0 until seqLen) {
                val dO = dOut[c][t]
                db[c] += dO
                for (c_in in 0 until inChannels) {
                    val w_c_in = w_c[c_in]
                    val dw_c_in = dw_c[c_in]
                    val v0 = if (t - 2 * dilation >= 0) convState.x[c_in][t - 2 * dilation] else 0.0f
                    val v1 = if (t - dilation >= 0) convState.x[c_in][t - dilation] else 0.0f
                    val v2 = convState.x[c_in][t]
                    
                    dw_c_in[0] += dO * v0
                    dw_c_in[1] += dO * v1
                    dw_c_in[2] += dO * v2
                    
                    dInput[c_in][t] += dO * w_c_in[2]
                    if (t - dilation >= 0) {
                        dInput[c_in][t - dilation] += dO * w_c_in[1]
                    }
                    if (t - 2 * dilation >= 0) {
                        dInput[c_in][t - 2 * dilation] += dO * w_c_in[0]
                    }
                }
            }
        }
        
        accumulateGrad3D(grads, "$layerPath.weight", dw)
        accumulateGrad1D(grads, "$layerPath.bias", db)

        return dInput
    }

    private fun backwardDownsample(
        grads: MutableMap<String, Any>,
        dOut: Array<FloatArray>,
        dsState: DownsampleState,
        inChannels: Int,
        outChannels: Int,
        layerPath: String
    ): Array<FloatArray> {
        val seqLen = dOut[0].size
        val w = weights["$layerPath.weight"] as Array<Array<FloatArray>>
        
        val dw = Array(outChannels) { Array(inChannels) { FloatArray(1) } }
        val db = FloatArray(outChannels)
        val dInput = Array(inChannels) { FloatArray(seqLen) }
        
        for (c in 0 until outChannels) {
            val w_c = w[c]
            val dw_c = dw[c]
            for (t in 0 until seqLen) {
                val dO = dOut[c][t]
                db[c] += dO
                for (c_in in 0 until inChannels) {
                    val w_c_in = w_c[c_in]
                    val dw_c_in = dw_c[c_in]
                    dw_c_in[0] += dO * dsState.x[c_in][t]
                    dInput[c_in][t] += dO * w_c_in[0]
                }
            }
        }
        
        accumulateGrad3D(grads, "$layerPath.weight", dw)
        accumulateGrad1D(grads, "$layerPath.bias", db)

        return dInput
    }

    private fun accumulateGrad1D(grads: MutableMap<String, Any>, key: String, grad: FloatArray) {
        val target = grads[key] as FloatArray
        for (i in target.indices) {
            target[i] += grad[i]
        }
    }

    private fun accumulateGrad2D(grads: MutableMap<String, Any>, key: String, grad: Array<FloatArray>) {
        val target = grads[key] as Array<FloatArray>
        for (i in target.indices) {
            for (j in target[i].indices) {
                target[i][j] += grad[i][j]
            }
        }
    }

    private fun accumulateGrad3D(grads: MutableMap<String, Any>, key: String, grad: Array<Array<FloatArray>>) {
        val target = grads[key] as Array<Array<FloatArray>>
        for (i in target.indices) {
            for (j in target[i].indices) {
                for (k in target[i][j].indices) {
                    target[i][j][k] += grad[i][j][k]
                }
            }
        }
    }

    /** Allocate a fresh gradient container with the same shapes as [weights]. */
    private fun allocGrads(): MutableMap<String, Any> {
        val g = HashMap<String, Any>(weights.size)
        for ((k, w) in weights) {
            g[k] = when (w) {
                is Array<*> -> if (w.isNotEmpty() && w[0] is FloatArray) {
                    val w2 = w as Array<FloatArray>
                    Array(w2.size) { FloatArray(w2[0].size) }
                } else {
                    val w3 = w as Array<Array<FloatArray>>
                    Array(w3.size) { Array(w3[0].size) { FloatArray(w3[0][0].size) } }
                }
                is FloatArray -> FloatArray(w.size)
                else -> throw IllegalStateException("unexpected weight type")
            }
        }
        return g
    }

    /** Zero every tensor in a gradient map (reused across batches per worker). */
    private fun zeroGradMap(grads: MutableMap<String, Any>) {
        for ((_, grad) in grads) {
            when (grad) {
                is Array<*> -> if (grad.isNotEmpty() && grad[0] is FloatArray) {
                    (grad as Array<FloatArray>).forEach { it.fill(0f) }
                } else {
                    (grad as Array<Array<FloatArray>>).forEach { m -> m.forEach { it.fill(0f) } }
                }
                is FloatArray -> grad.fill(0f)
            }
        }
    }

    /** Sum a worker's gradient map into the main [gradients] (main thread). */
    private fun reduceGrads(src: Map<String, Any>) {
        for ((k, s) in src) {
            val d = gradients[k]!!
            when (s) {
                is Array<*> -> if (s.isNotEmpty() && s[0] is FloatArray) {
                    val sa = s as Array<FloatArray>; val da = d as Array<FloatArray>
                    for (i in sa.indices) { val srow = sa[i]; val drow = da[i]; for (j in srow.indices) drow[j] += srow[j] }
                } else {
                    val sa = s as Array<Array<FloatArray>>; val da = d as Array<Array<FloatArray>>
                    for (i in sa.indices) for (j in sa[i].indices) { val srow = sa[i][j]; val drow = da[i][j]; for (kk in srow.indices) drow[kk] += srow[kk] }
                }
                is FloatArray -> { val da = d as FloatArray; for (i in s.indices) da[i] += s[i] }
            }
        }
    }

    private fun zeroGradients() {
        for ((_, grad) in gradients) {
            when (grad) {
                is Array<*> -> {
                    if (grad[0] is FloatArray) {
                        val g2d = grad as Array<FloatArray>
                        for (i in g2d.indices) {
                            g2d[i].fill(0.0f)
                        }
                    } else {
                        val g3d = grad as Array<Array<FloatArray>>
                        for (i in g3d.indices) {
                            for (j in g3d[i].indices) {
                                g3d[i][j].fill(0.0f)
                            }
                        }
                    }
                }
                is FloatArray -> {
                    grad.fill(0.0f)
                }
            }
        }
    }

    // Adam optimizer parameter update. [weightDecay] is decoupled (AdamW-style):
    // applied directly to the weights, not folded into the gradient/moments, and
    // skipped for bias vectors. It pulls weights gently toward zero each step,
    // which curbs overfitting on the small on-device datasets.
    private fun stepAdam(
        lr: Float,
        beta1: Float,
        beta2: Float,
        epsilon: Float,
        batchSize: Int,
        weightDecay: Float
    ) {
        t++
        val biasCorrection1 = 1.0f - beta1.pow(t)
        val biasCorrection2 = 1.0f - beta2.pow(t)

        for ((k, w) in weights) {
            val grad = gradients[k]!!
            val mk = m[k]!!
            val vk = v[k]!!
            // Decay weight tensors only — never biases.
            val decay = if (k.endsWith(".bias")) 0f else weightDecay

            when (w) {
                is Array<*> -> {
                    if (w[0] is FloatArray) {
                        val w2d = w as Array<FloatArray>
                        val g2d = grad as Array<FloatArray>
                        val m2d = mk as Array<FloatArray>
                        val v2d = vk as Array<FloatArray>

                        for (i in w2d.indices) {
                            for (j in w2d[i].indices) {
                                val g = g2d[i][j] / batchSize
                                m2d[i][j] = beta1 * m2d[i][j] + (1f - beta1) * g
                                v2d[i][j] = beta2 * v2d[i][j] + (1f - beta2) * g * g

                                val mHat = m2d[i][j] / biasCorrection1
                                val vHat = v2d[i][j] / biasCorrection2

                                w2d[i][j] -= lr * (mHat / (sqrt(vHat) + epsilon) + decay * w2d[i][j])
                            }
                        }
                    } else {
                        val w3d = w as Array<Array<FloatArray>>
                        val g3d = grad as Array<Array<FloatArray>>
                        val m3d = mk as Array<Array<FloatArray>>
                        val v3d = vk as Array<Array<FloatArray>>

                        for (i in w3d.indices) {
                            for (j in w3d[i].indices) {
                                for (k_idx in w3d[i][j].indices) {
                                    val g = g3d[i][j][k_idx] / batchSize
                                    m3d[i][j][k_idx] = beta1 * m3d[i][j][k_idx] + (1f - beta1) * g
                                    v3d[i][j][k_idx] = beta2 * v3d[i][j][k_idx] + (1f - beta2) * g * g

                                    val mHat = m3d[i][j][k_idx] / biasCorrection1
                                    val vHat = v3d[i][j][k_idx] / biasCorrection2

                                    w3d[i][j][k_idx] -= lr * (mHat / (sqrt(vHat) + epsilon) + decay * w3d[i][j][k_idx])
                                }
                            }
                        }
                    }
                }
                is FloatArray -> {
                    val w1d = w as FloatArray
                    val g1d = grad as FloatArray
                    val m1d = mk as FloatArray
                    val v1d = vk as FloatArray

                    for (i in w1d.indices) {
                        val g = g1d[i] / batchSize
                        m1d[i] = beta1 * m1d[i] + (1f - beta1) * g
                        v1d[i] = beta2 * v1d[i] + (1f - beta2) * g * g

                        val mHat = m1d[i] / biasCorrection1
                        val vHat = v1d[i] / biasCorrection2

                        w1d[i] -= lr * (mHat / (sqrt(vHat) + epsilon) + decay * w1d[i])
                    }
                }
            }
        }
    }

    /** Deep-copy the entire weight set, for best-epoch checkpointing. */
    private fun snapshotWeights(): Map<String, Any> {
        val snap = HashMap<String, Any>(weights.size)
        for ((k, w) in weights) {
            snap[k] = when (w) {
                is Array<*> -> {
                    if (w.isNotEmpty() && w[0] is FloatArray) {
                        val w2d = w as Array<FloatArray>
                        Array(w2d.size) { i -> w2d[i].copyOf() }
                    } else {
                        val w3d = w as Array<Array<FloatArray>>
                        Array(w3d.size) { i -> Array(w3d[i].size) { j -> w3d[i][j].copyOf() } }
                    }
                }
                is FloatArray -> w.copyOf()
                else -> w
            }
        }
        return snap
    }

    /** Copy a previously taken [snapshotWeights] back into the live weights. */
    private fun restoreWeights(snap: Map<String, Any>) {
        for ((k, s) in snap) {
            val dst = weights[k] ?: continue
            when (s) {
                is Array<*> -> {
                    if (s.isNotEmpty() && s[0] is FloatArray) {
                        val src = s as Array<FloatArray>
                        val d = dst as Array<FloatArray>
                        for (i in src.indices) src[i].copyInto(d[i])
                    } else {
                        val src = s as Array<Array<FloatArray>>
                        val d = dst as Array<Array<FloatArray>>
                        for (i in src.indices) for (j in src[i].indices) src[i][j].copyInto(d[i][j])
                    }
                }
                is FloatArray -> s.copyInto(dst as FloatArray)
            }
        }
    }

    // Run complete training loop
    fun fit(
        trainX: List<Array<FloatArray>>,
        trainY: List<String>,
        valX: List<Array<FloatArray>>,
        valY: List<String>,
        epochs: Int,
        onProgress: (Int, Float, Float) -> Unit
    ) {
        val numClasses = classes.size
        val labelToIdx = classes.withIndex().associate { it.value to it.index }

        val trainTargets = IntArray(trainY.size) { labelToIdx[trainY[it]] ?: 0 }
        val valTargets = IntArray(valY.size) { labelToIdx[valY[it]] ?: 0 }

        bestValAccuracy = 0f
        var bestSnapshot: Map<String, Any>? = null
        // Early stopping: if validation accuracy hasn't improved for `patience`
        // epochs, stop — the best-checkpoint weights are already saved, so this only
        // skips wasted late epochs and makes training noticeably faster.
        val patience = maxOf(8, epochs / 4)
        var epochsSinceBest = 0

        // Class-balanced sample weights ("balanced" scheme): weight = N /
        // (numClasses * count[class]). Rare gestures then contribute as much
        // total gradient as a dominant class like REST, so they stop being
        // ignored. Weights average to ~1 across the set, leaving the effective
        // learning-rate scale unchanged.
        val classCounts = IntArray(numClasses)
        for (target in trainTargets) classCounts[target]++
        val totalTrain = trainTargets.size.toFloat()
        val classWeights = FloatArray(numClasses) { c ->
            if (classCounts[c] > 0) totalTrain / (numClasses * classCounts[c]) else 1f
        }

        // Session-invariant transform, then z-score standardization with
        // statistics from the training set only (no leakage from validation)
        val transformedTrain = trainX.map { seq -> Array(seq.size) { t -> FeatureTransform.apply(seq[t]) } }
        val transformedVal = valX.map { seq -> Array(seq.size) { t -> FeatureTransform.apply(seq[t]) } }
        val (mean, std) = FeatureTransform.computeStats(transformedTrain)
        featureMean = mean
        featureStd = std
        // Session anchor for runtime drift compensation (computed from raw REST levels).
        restBaseline = FeatureTransform.restBaselineFromSequences(trainX, trainY)
        val prepTrain = transformedTrain.map { seq -> Array(seq.size) { t -> FeatureTransform.standardize(seq[t], mean, std) } }
        val prepVal = transformedVal.map { seq -> Array(seq.size) { t -> FeatureTransform.standardize(seq[t], mean, std) } }

        val random = java.util.Random(42)
        val trainIndices = prepTrain.indices.toMutableList()

        // Cosine-decayed learning rate: start a touch higher, anneal toward a
        // small floor so late epochs fine-tune instead of bouncing around.
        val baseLr = 0.002f
        val minLr = 0.0002f
        val beta1 = 0.9f
        val beta2 = 0.999f
        val epsilon = 1e-8f
        // Decoupled L2 (AdamW-style) regularization — see stepAdam.
        val weightDecay = 1e-4f

        // Data augmentation: jitter each standardized frame with small Gaussian
        // noise every epoch. Cheap regularizer that makes the model robust to the
        // sensor noise it will actually see live, instead of memorizing windows.
        val jitterStd = 0.08f

        // Drift augmentation: once per (sequence, epoch) shift whole accel channels
        // by ±1 quantization LEVEL with probability driftProb. This simulates the
        // boot-to-boot calibration shift that makes a model fail after the glove is
        // power-cycled, so the model learns to tolerate it. A constant raw-level
        // offset d on channel i equals d/std[i] in standardized space (we add it
        // after standardization). Accel channels (0..17) only — gyro is a rate.
        val driftProb = 0.2
        val stdForDrift = featureStd

        // Mini-batch gradient accumulation: smoother updates than per-sample steps
        val batchSize = 8

        // Data-parallel training: each mini-batch's samples are processed across
        // CPU cores. forward()/backward() only READ the weights and write into a
        // per-worker gradient map, which is summed before the (serial) Adam step.
        // The result is identical to the old single-threaded path — same batch
        // size and update count — just computed on every core instead of one.
        val nWorkers = maxOf(1, minOf(batchSize, Runtime.getRuntime().availableProcessors()))
        val pool = Executors.newFixedThreadPool(nWorkers)
        val workerGrads = Array(nWorkers) { allocGrads() }
        // One RNG per worker (java.util.Random is synchronized → contended).
        val jitterRng = Array(nWorkers) { java.util.Random(1000L + it) }

        try {
        for (epoch in 0 until epochs) {
            // Shuffle training set per epoch
            trainIndices.shuffle(random)

            // Cosine schedule from baseLr down to minLr over all epochs.
            val lr = if (epochs <= 1) baseLr else {
                val cosArg = Math.PI * epoch / (epochs - 1)
                minLr + 0.5f * (baseLr - minLr) * (1f + kotlin.math.cos(cosArg).toFloat())
            }

            var totalLoss = 0.0f
            var start = 0
            while (start < trainIndices.size) {
                val end = minOf(start + batchSize, trainIndices.size)
                val batch = trainIndices.subList(start, end)
                val bSize = batch.size
                start = end

                zeroGradients()
                val nw = minOf(nWorkers, bSize)
                val futures = (0 until nw).map { w ->
                    pool.submit(Callable {
                        val g = workerGrads[w]; zeroGradMap(g)
                        val rng = jitterRng[w]
                        var partial = 0.0f
                        var s = w
                        while (s < bSize) {
                            val idx = batch[s]
                            val clean = prepTrain[idx]
                            val target = trainTargets[idx]
                            // Per-sequence accel drift (±1 level / std), constant across the
                            // window, plus fresh per-frame Gaussian jitter. Validation is
                            // never augmented.
                            val drift = FloatArray(18) { fi ->
                                val r = rng.nextDouble()
                                val lvl = if (r < driftProb) -1f else if (r > 1.0 - driftProb) 1f else 0f
                                if (lvl == 0f) 0f else lvl / stdForDrift[fi]
                            }
                            val seq = Array(clean.size) { ti ->
                                val frame = clean[ti]
                                FloatArray(frame.size) { fi ->
                                    frame[fi] + rng.nextGaussian().toFloat() * jitterStd +
                                        (if (fi < 18) drift[fi] else 0f)
                                }
                            }
                            val state = forward(seq, numClasses)
                            val sampleWeight = classWeights[target]
                            val maxLogit = state.logits.maxOrNull() ?: 0.0f
                            var sumExp = 0.0f
                            for (logit in state.logits) sumExp += exp((logit - maxLogit).toDouble()).toFloat()
                            val logSoftmax = state.logits[target] - maxLogit - kotlin.math.ln(sumExp.toDouble()).toFloat()
                            partial -= sampleWeight * logSoftmax
                            backward(g, state, target, numClasses, sampleWeight)
                            s += nw
                        }
                        partial
                    })
                }
                for (f in futures) totalLoss += f.get()
                for (w in 0 until nw) reduceGrads(workerGrads[w])
                stepAdam(lr, beta1, beta2, epsilon, bSize, weightDecay)
            }

            val avgLoss = totalLoss / prepTrain.size

            // Evaluate on validation set
            val valAcc = evaluate(prepVal, valTargets)

            // Best-checkpoint: remember the weights at the highest val accuracy.
            // Strict '>' keeps the earliest epoch that reaches a given accuracy,
            // which tends to be the least overfit.
            if (prepVal.isNotEmpty() && valAcc > bestValAccuracy) {
                bestValAccuracy = valAcc
                bestSnapshot = snapshotWeights()
                epochsSinceBest = 0
            } else {
                epochsSinceBest++
            }

            // Call progress update
            onProgress(epoch + 1, avgLoss, valAcc)

            Log.d(TAG, "Epoch ${epoch + 1}/$epochs - Loss: ${String.format(Locale.US, "%.4f", avgLoss)} - Val Acc: ${String.format(Locale.US, "%.2f", valAcc * 100)}%")

            // Early stop once validation has plateaued (best weights already kept).
            if (prepVal.isNotEmpty() && epochsSinceBest >= patience) {
                Log.d(TAG, "Early stop at epoch ${epoch + 1} (no val gain for $patience epochs).")
                break
            }
        }
        } finally {
            pool.shutdown()
        }

        // Restore the best epoch's weights so the saved model is the best one we
        // saw, not whatever the final epoch happened to land on.
        bestSnapshot?.let {
            restoreWeights(it)
            Log.d(TAG, "Restored best checkpoint (val acc ${String.format(Locale.US, "%.2f", bestValAccuracy * 100)}%)")
        }
    }

    // Calculate prediction accuracy on sequence validation set
    private fun evaluate(testSequences: List<Array<FloatArray>>, testTargets: IntArray): Float {
        if (testSequences.isEmpty()) return 0f
        var correct = 0
        val numClasses = classes.size
        for (i in testSequences.indices) {
            val seq = testSequences[i]
            val state = forward(seq, numClasses)
            
            var maxIdx = 0
            var maxVal = state.logits[0]
            for (c in 1 until numClasses) {
                if (state.logits[c] > maxVal) {
                    maxVal = state.logits[c]
                    maxIdx = c
                }
            }
            if (maxIdx == testTargets[i]) {
                correct++
            }
        }
        return correct.toFloat() / testSequences.size
    }

    // Save weights and label mapping to JSON
    fun saveToModelsDir(context: Context, modelName: String): File? {
        val dir = context.getExternalFilesDir("models") ?: return null
        dir.mkdirs()
        val file = File(dir, if (modelName.endsWith(".json")) modelName else "$modelName.json")
        return try {
            saveToFile(file)
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save TCN model: ${e.message}", e)
            null
        }
    }

    private fun saveToFile(file: File) {
        val json = JSONObject()
        json.put("model_type", "tcn")
        json.put("feature_version", FeatureTransform.VERSION)
        json.put("num_features", NUM_FEATURES)
        json.put("seq_len", SEQ_LEN)
        json.put("pooling", "mean")
        // Best validation accuracy reached during training (informational; older
        // loaders simply ignore the extra key).
        json.put("val_accuracy", bestValAccuracy.toDouble())

        val meanArr = JSONArray()
        featureMean.forEach { meanArr.put(it.toDouble()) }
        json.put("feature_mean", meanArr)
        val stdArr = JSONArray()
        featureStd.forEach { stdArr.put(it.toDouble()) }
        json.put("feature_std", stdArr)

        // Session anchor for runtime drift compensation (omitted if no REST class).
        restBaseline?.let { rb ->
            val rbArr = JSONArray()
            rb.forEach { rbArr.put(it.toDouble()) }
            json.put("rest_baseline", rbArr)
        }

        val labelsArr = JSONArray()
        classes.forEach { labelsArr.put(it) }
        json.put("labels", labelsArr)
        json.put("classes", labelsArr)
        
        val weightsJson = JSONObject()
        for ((k, w) in weights) {
            when (w) {
                is Array<*> -> {
                    if (w[0] is FloatArray) {
                        val w2d = w as Array<FloatArray>
                        val outerArr = JSONArray()
                        for (row in w2d) {
                            val innerArr = JSONArray()
                            for (v in row) {
                                innerArr.put(v.toDouble())
                            }
                            outerArr.put(innerArr)
                        }
                        weightsJson.put(k, outerArr)
                    } else {
                        val w3d = w as Array<Array<FloatArray>>
                        val outerArr = JSONArray()
                        for (matrix in w3d) {
                            val midArr = JSONArray()
                            for (row in matrix) {
                                val innerArr = JSONArray()
                                for (v in row) {
                                    innerArr.put(v.toDouble())
                                }
                                midArr.put(innerArr)
                            }
                            outerArr.put(midArr)
                        }
                        weightsJson.put(k, outerArr)
                    }
                }
                is FloatArray -> {
                    val arr = JSONArray()
                    for (v in w) {
                        arr.put(v.toDouble())
                    }
                    weightsJson.put(k, arr)
                }
            }
        }
        json.put("weights", weightsJson)
        
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
        Log.d(TAG, "Successfully serialized TCN weights to ${file.absolutePath}")
    }
}
