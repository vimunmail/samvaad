package com.example.xgestalkble.ml

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * On-device trainer for a single-layer LSTM gesture classifier.
 *
 * It consumes the SAME 30-frame sequences as the TCN (build them with
 * [TcnTrainer.createTcnSequencesSplit]) and the SAME 21-feature CSV layout, so
 * LSTM is a drop-in third option next to RFC and TCN — no separate data prep.
 *
 * Mirrors the TCN trainer's quality tricks so the two are comparable:
 *   • z-score standardization with TRAIN-only statistics (no val leakage)
 *   • class-balanced loss so rare gestures aren't ignored
 *   • per-epoch Gaussian jitter augmentation
 *   • cosine-decayed LR + decoupled (AdamW) weight decay
 *   • best-validation checkpoint restore + early stopping
 *
 * Architecture: one LSTM layer (gates packed [i, f, g, o]) → mean-pool every
 * hidden state over the window → linear → softmax. Hidden size [HIDDEN].
 * The forward pass here is the reference [LstmClassifier] must reproduce.
 */
class LstmTrainer(
    val classes: Array<String>,
    private val numFeatures: Int = FeatureTransform.NUM_FEATURES,
    private val hidden: Int = HIDDEN
) {
    companion object {
        private const val TAG = "LstmTrainer"
        const val HIDDEN = 64
        private const val SEQ_LEN = 30
        // Label smoothing: train toward (1 - eps) for the true class instead of a
        // hard 1.0, so the LSTM stops driving the winning softmax to ~100%. This
        // curbs the over-confident false positives an idle/unseen input otherwise
        // triggers, and makes the confidence/margin gates meaningful again.
        private const val LABEL_SMOOTH = 0.1f
    }

    private val g4 = 4 * hidden

    // ── Parameters: gates packed [i, f, g, o] along the 4H axis ──
    private val wIh = Array(g4) { FloatArray(numFeatures) }   // input → gates
    private val wHh = Array(g4) { FloatArray(hidden) }        // hidden → gates
    private val bias = FloatArray(g4)
    private val linW = Array(classes.size) { FloatArray(hidden) }
    private val linB = FloatArray(classes.size)

    // Gradients
    private val dWih = Array(g4) { FloatArray(numFeatures) }
    private val dWhh = Array(g4) { FloatArray(hidden) }
    private val dBias = FloatArray(g4)
    private val dLinW = Array(classes.size) { FloatArray(hidden) }
    private val dLinB = FloatArray(classes.size)

    // Adam moments
    private val mWih = Array(g4) { FloatArray(numFeatures) }; private val vWih = Array(g4) { FloatArray(numFeatures) }
    private val mWhh = Array(g4) { FloatArray(hidden) };      private val vWhh = Array(g4) { FloatArray(hidden) }
    private val mBias = FloatArray(g4);                       private val vBias = FloatArray(g4)
    private val mLinW = Array(classes.size) { FloatArray(hidden) }; private val vLinW = Array(classes.size) { FloatArray(hidden) }
    private val mLinB = FloatArray(classes.size);            private val vLinB = FloatArray(classes.size)
    private var adamT = 0

    // Standardization stats (train-only), saved with the model.
    private var featureMean = FloatArray(numFeatures)
    private var featureStd = FloatArray(numFeatures) { 1f }

    // Per-channel mean of REST frames, saved as `rest_baseline` for runtime drift
    // compensation (SessionCalibrator). Null ⇒ no REST class ⇒ compensation off.
    private var restBaseline: FloatArray? = null

    var bestValAccuracy: Float = 0f
        private set

    init {
        val rnd = java.util.Random(42)
        val scale = (1.0 / sqrt(hidden.toDouble())).toFloat()
        for (k in 0 until g4) {
            for (j in 0 until numFeatures) wIh[k][j] = rnd.nextGaussian().toFloat() * scale
            for (j in 0 until hidden) wHh[k][j] = rnd.nextGaussian().toFloat() * scale
        }
        // Forget-gate bias = 1 (classic LSTM trick: don't forget early in training).
        for (n in 0 until hidden) bias[hidden + n] = 1f
        val lScale = (1.0 / sqrt(hidden.toDouble())).toFloat()
        for (c in classes.indices) for (i in 0 until hidden) linW[c][i] = rnd.nextGaussian().toFloat() * lScale
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    // ── Forward state cached for BPTT ──
    private class Cache(seqLen: Int, h: Int, inp: Int) {
        val xs = Array(seqLen) { FloatArray(inp) }
        val ig = Array(seqLen) { FloatArray(h) }
        val fg = Array(seqLen) { FloatArray(h) }
        val gg = Array(seqLen) { FloatArray(h) }
        val og = Array(seqLen) { FloatArray(h) }
        val cPrev = Array(seqLen) { FloatArray(h) }
        val hPrev = Array(seqLen) { FloatArray(h) }
        val tanhC = Array(seqLen) { FloatArray(h) }
        val hOut = Array(seqLen) { FloatArray(h) }
        val pooled = FloatArray(h)
        val logits = FloatArray(0)
    }

    private fun forward(seq: Array<FloatArray>): Pair<Cache, FloatArray> {
        val seqLen = seq.size
        val cache = Cache(seqLen, hidden, numFeatures)
        val hState = FloatArray(hidden)
        val cState = FloatArray(hidden)
        val gate = FloatArray(g4)

        for (t in 0 until seqLen) {
            val x = seq[t]
            x.copyInto(cache.xs[t])
            hState.copyInto(cache.hPrev[t])
            cState.copyInto(cache.cPrev[t])

            for (k in 0 until g4) {
                var s = bias[k]
                val wih = wIh[k]
                for (j in x.indices) s += wih[j] * x[j]
                val whh = wHh[k]
                for (j in 0 until hidden) s += whh[j] * hState[j]
                gate[k] = s
            }
            for (n in 0 until hidden) {
                val i = sigmoid(gate[n])
                val f = sigmoid(gate[hidden + n])
                val gv = tanh(gate[2 * hidden + n].toDouble()).toFloat()
                val o = sigmoid(gate[3 * hidden + n])
                val c = f * cState[n] + i * gv
                val tc = tanh(c.toDouble()).toFloat()
                cState[n] = c
                hState[n] = o * tc
                cache.ig[t][n] = i; cache.fg[t][n] = f; cache.gg[t][n] = gv; cache.og[t][n] = o
                cache.tanhC[t][n] = tc; cache.hOut[t][n] = hState[n]
                cache.pooled[n] += hState[n]
            }
        }
        for (n in 0 until hidden) cache.pooled[n] /= seqLen

        val logits = FloatArray(classes.size)
        for (c in classes.indices) {
            var sum = linB[c]
            val w = linW[c]
            for (i in 0 until hidden) sum += w[i] * cache.pooled[i]
            logits[c] = sum
        }
        return Pair(cache, logits)
    }

    /**
     * Per-thread gradient accumulator. Workers each fill their own [Grads] during
     * a mini-batch (no shared writes → safe to run on multiple cores), then the
     * main thread sums them into the trainer's gradients before the Adam step.
     */
    private inner class Grads {
        val wih = Array(g4) { FloatArray(numFeatures) }
        val whh = Array(g4) { FloatArray(hidden) }
        val bias = FloatArray(g4)
        val linW = Array(classes.size) { FloatArray(hidden) }
        val linB = FloatArray(classes.size)
        var loss = 0f
        fun zero() {
            for (k in 0 until g4) { java.util.Arrays.fill(wih[k], 0f); java.util.Arrays.fill(whh[k], 0f) }
            java.util.Arrays.fill(bias, 0f)
            for (c in classes.indices) java.util.Arrays.fill(linW[c], 0f)
            java.util.Arrays.fill(linB, 0f)
            loss = 0f
        }
    }

    /** Sum a worker's [Grads] into the trainer's reduction buffers (main thread). */
    private fun reduceWorker(g: Grads) {
        for (k in 0 until g4) {
            val a = dWih[k]; val b = g.wih[k]; for (j in a.indices) a[j] += b[j]
            val c = dWhh[k]; val d = g.whh[k]; for (j in c.indices) c[j] += d[j]
            dBias[k] += g.bias[k]
        }
        for (c in classes.indices) {
            val a = dLinW[c]; val b = g.linW[c]; for (j in a.indices) a[j] += b[j]
            dLinB[c] += g.linB[c]
        }
    }

    /**
     * Backprop one sample into [g] (thread-local). Only READS the shared weights,
     * so many of these run concurrently on different samples. [sampleWeight]
     * applies class-balanced scaling.
     */
    private fun backwardInto(g: Grads, cache: Cache, logits: FloatArray, targetIdx: Int, sampleWeight: Float) {
        val seqLen = cache.xs.size
        // Softmax + cross-entropy gradient on logits.
        val maxLogit = logits.maxOrNull() ?: 0f
        var sumExp = 0f
        val probs = FloatArray(logits.size)
        for (c in logits.indices) { probs[c] = exp((logits[c] - maxLogit).toDouble()).toFloat(); sumExp += probs[c] }
        for (c in logits.indices) probs[c] = if (sumExp > 0f) probs[c] / sumExp else 0f

        // Smoothed target: (1 - eps) on the true class, eps/(K-1) spread over the
        // rest. Gradient is probs - smoothedTarget.
        val k = logits.size
        val offTarget = if (k > 1) LABEL_SMOOTH / (k - 1) else 0f
        val dLogits = FloatArray(k) { c ->
            val q = if (c == targetIdx) 1f - LABEL_SMOOTH else offTarget
            sampleWeight * (probs[c] - q)
        }

        // Linear backward → gradient w.r.t. pooled representation.
        val dPooled = FloatArray(hidden)
        for (c in classes.indices) {
            g.linB[c] += dLogits[c]
            val w = linW[c]; val dw = g.linW[c]
            for (i in 0 until hidden) {
                dw[i] += dLogits[c] * cache.pooled[i]
                dPooled[i] += dLogits[c] * w[i]
            }
        }

        // Mean pooling spreads the gradient evenly to every timestep's hidden.
        val dhPool = FloatArray(hidden) { dPooled[it] / seqLen }

        // BPTT
        val dcNext = FloatArray(hidden)
        val dhNext = FloatArray(hidden)
        val gatePre = FloatArray(g4)
        for (t in seqLen - 1 downTo 0) {
            for (n in 0 until hidden) {
                val dh = dhPool[n] + dhNext[n]
                val o = cache.og[t][n]; val tc = cache.tanhC[t][n]
                val doo = dh * tc
                val dc = dcNext[n] + dh * o * (1f - tc * tc)
                val i = cache.ig[t][n]; val f = cache.fg[t][n]; val gv = cache.gg[t][n]
                val cprev = cache.cPrev[t][n]
                val di = dc * gv
                val dg = dc * i
                val df = dc * cprev
                dcNext[n] = dc * f  // carry cell-state grad to previous step
                gatePre[n]            = di * i * (1f - i)
                gatePre[hidden + n]   = df * f * (1f - f)
                gatePre[2 * hidden + n] = dg * (1f - gv * gv)
                gatePre[3 * hidden + n] = doo * o * (1f - o)
            }
            // Accumulate weight grads + propagate to previous hidden.
            val xT = cache.xs[t]; val hP = cache.hPrev[t]
            java.util.Arrays.fill(dhNext, 0f)
            for (k2 in 0 until g4) {
                val gp = gatePre[k2]
                if (gp == 0f) continue
                g.bias[k2] += gp
                val dwih = g.wih[k2]
                for (j in xT.indices) dwih[j] += gp * xT[j]
                val whh = wHh[k2]; val dwhh = g.whh[k2]
                for (j in 0 until hidden) {
                    dwhh[j] += gp * hP[j]
                    dhNext[j] += whh[j] * gp
                }
            }
        }
    }

    private fun zeroGrads() {
        for (k in 0 until g4) { java.util.Arrays.fill(dWih[k], 0f); java.util.Arrays.fill(dWhh[k], 0f) }
        java.util.Arrays.fill(dBias, 0f)
        for (c in classes.indices) java.util.Arrays.fill(dLinW[c], 0f)
        java.util.Arrays.fill(dLinB, 0f)
    }

    private fun adamStep(lr: Float, b1: Float, b2: Float, eps: Float, batch: Int, decay: Float) {
        adamT++
        val bc1 = 1f - b1.pow(adamT); val bc2 = 1f - b2.pow(adamT)
        fun upd(w: FloatArray, g: FloatArray, m: FloatArray, v: FloatArray, wd: Float) {
            for (i in w.indices) {
                val grad = g[i] / batch
                m[i] = b1 * m[i] + (1f - b1) * grad
                v[i] = b2 * v[i] + (1f - b2) * grad * grad
                val mh = m[i] / bc1; val vh = v[i] / bc2
                w[i] -= lr * (mh / (sqrt(vh) + eps) + wd * w[i])
            }
        }
        for (k in 0 until g4) { upd(wIh[k], dWih[k], mWih[k], vWih[k], decay); upd(wHh[k], dWhh[k], mWhh[k], vWhh[k], decay) }
        upd(bias, dBias, mBias, vBias, 0f) // never decay biases
        for (c in classes.indices) upd(linW[c], dLinW[c], mLinW[c], vLinW[c], decay)
        upd(linB, dLinB, mLinB, vLinB, 0f)
    }

    private fun snapshot(): Map<String, Any> = mapOf(
        "wIh" to Array(g4) { wIh[it].copyOf() },
        "wHh" to Array(g4) { wHh[it].copyOf() },
        "bias" to bias.copyOf(),
        "linW" to Array(classes.size) { linW[it].copyOf() },
        "linB" to linB.copyOf()
    )

    @Suppress("UNCHECKED_CAST")
    private fun restore(snap: Map<String, Any>) {
        (snap["wIh"] as Array<FloatArray>).forEachIndexed { i, r -> r.copyInto(wIh[i]) }
        (snap["wHh"] as Array<FloatArray>).forEachIndexed { i, r -> r.copyInto(wHh[i]) }
        (snap["bias"] as FloatArray).copyInto(bias)
        (snap["linW"] as Array<FloatArray>).forEachIndexed { i, r -> r.copyInto(linW[i]) }
        (snap["linB"] as FloatArray).copyInto(linB)
    }

    fun fit(
        trainX: List<Array<FloatArray>>,
        trainY: List<String>,
        valX: List<Array<FloatArray>>,
        valY: List<String>,
        epochs: Int,
        onProgress: (Int, Float, Float) -> Unit
    ) {
        val labelToIdx = classes.withIndex().associate { it.value to it.index }
        val trainTargets = IntArray(trainY.size) { labelToIdx[trainY[it]] ?: 0 }
        val valTargets = IntArray(valY.size) { labelToIdx[valY[it]] ?: 0 }

        bestValAccuracy = 0f
        var bestSnap: Map<String, Any>? = null
        val patience = maxOf(8, epochs / 4)
        var sinceBest = 0

        // Class-balanced weights (average ≈ 1, so the LR scale is unchanged).
        val numClasses = classes.size
        val counts = IntArray(numClasses)
        for (tg in trainTargets) counts[tg]++
        val total = trainTargets.size.toFloat()
        val classW = FloatArray(numClasses) { c -> if (counts[c] > 0) total / (numClasses * counts[c]) else 1f }

        // Transform + standardize with train-only stats.
        val tTrain = trainX.map { seq -> Array(seq.size) { t -> FeatureTransform.apply(seq[t]) } }
        val tVal = valX.map { seq -> Array(seq.size) { t -> FeatureTransform.apply(seq[t]) } }
        val (mean, std) = FeatureTransform.computeStats(tTrain)
        featureMean = mean; featureStd = std
        restBaseline = FeatureTransform.restBaselineFromSequences(trainX, trainY)
        val prepTrain = tTrain.map { seq -> Array(seq.size) { t -> FeatureTransform.standardize(seq[t], mean, std) } }
        val prepVal = tVal.map { seq -> Array(seq.size) { t -> FeatureTransform.standardize(seq[t], mean, std) } }

        val rnd = java.util.Random(42)
        val order = prepTrain.indices.toMutableList()
        val baseLr = 0.005f; val minLr = 0.0005f
        val b1 = 0.9f; val b2 = 0.999f; val eps = 1e-8f
        val weightDecay = 1e-4f
        val jitterStd = 0.08f
        // Drift augmentation: see TcnTrainer. Per-sequence ±1 accel-level shift
        // (in standardized space, d/std) so the LSTM survives a glove power-cycle.
        val driftProb = 0.2
        val stdForDrift = featureStd
        val batchSize = 32

        // Data-parallel training: the samples of each mini-batch are spread across
        // CPU cores. forward()/backwardInto() only read the weights, so this is
        // safe; per-worker Grads are summed before the (serial) Adam step.
        val nWorkers = maxOf(1, minOf(batchSize, Runtime.getRuntime().availableProcessors()))
        val pool = Executors.newFixedThreadPool(nWorkers)
        val workerGrads = Array(nWorkers) { Grads() }
        // One RNG per worker (java.util.Random is synchronized → contended; this
        // avoids that and keeps the jitter noise independent per core).
        val jitterRng = Array(nWorkers) { java.util.Random(1000L + it) }

        try {
            for (epoch in 0 until epochs) {
                order.shuffle(rnd)
                val lr = if (epochs <= 1) baseLr else {
                    val cosArg = Math.PI * epoch / (epochs - 1)
                    minLr + 0.5f * (baseLr - minLr) * (1f + kotlin.math.cos(cosArg).toFloat())
                }

                var totalLoss = 0f
                var start = 0
                while (start < order.size) {
                    val end = minOf(start + batchSize, order.size)
                    val batch = order.subList(start, end)
                    val bSize = batch.size
                    start = end

                    zeroGrads()
                    val nw = minOf(nWorkers, bSize)
                    val futures = (0 until nw).map { w ->
                        pool.submit {
                            val g = workerGrads[w]; g.zero()
                            val rng = jitterRng[w]
                            var s = w
                            while (s < bSize) {
                                val idx = batch[s]
                                val clean = prepTrain[idx]
                                val target = trainTargets[idx]
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
                                val (cache, logits) = forward(seq)
                                val sw = classW[target]
                                val maxLogit = logits.maxOrNull() ?: 0f
                                var sumExp = 0f
                                for (l in logits) sumExp += exp((l - maxLogit).toDouble()).toFloat()
                                val logSoftmax = logits[target] - maxLogit - ln(sumExp.toDouble()).toFloat()
                                g.loss -= sw * logSoftmax
                                backwardInto(g, cache, logits, target, sw)
                                s += nw
                            }
                        }
                    }
                    futures.forEach { it.get() }
                    for (w in 0 until nw) { reduceWorker(workerGrads[w]); totalLoss += workerGrads[w].loss }
                    adamStep(lr, b1, b2, eps, bSize, weightDecay)
                }

                val avgLoss = totalLoss / prepTrain.size.coerceAtLeast(1)
                val valAcc = evaluate(prepVal, valTargets)
                if (prepVal.isNotEmpty() && valAcc > bestValAccuracy) {
                    bestValAccuracy = valAcc; bestSnap = snapshot(); sinceBest = 0
                } else sinceBest++

                onProgress(epoch + 1, avgLoss, valAcc)
                Log.d(TAG, "Epoch ${epoch + 1}/$epochs - Loss ${String.format(Locale.US, "%.4f", avgLoss)} - Val ${String.format(Locale.US, "%.2f", valAcc * 100)}% (${nWorkers} cores)")

                // Early stop when target high accuracy / low loss is reached, or when validation accuracy stops improving
                if (prepVal.isNotEmpty()) {
                    if (valAcc >= 0.98f && avgLoss <= 0.05f && epoch >= 8) {
                        Log.d(TAG, "Fast convergence early stop at epoch ${epoch + 1} (Val Acc: ${valAcc * 100}%)")
                        break
                    }
                    if (sinceBest >= patience) {
                        Log.d(TAG, "Early stop at epoch ${epoch + 1}")
                        break
                    }
                }
            }
        } finally {
            pool.shutdown()
        }
        bestSnap?.let { restore(it); Log.d(TAG, "Restored best checkpoint (${String.format(Locale.US, "%.2f", bestValAccuracy * 100)}%)") }
    }

    private fun evaluate(seqs: List<Array<FloatArray>>, targets: IntArray): Float {
        if (seqs.isEmpty()) return 0f
        var correct = 0
        for (i in seqs.indices) {
            val (_, logits) = forward(seqs[i])
            var maxIdx = 0
            for (c in 1 until logits.size) if (logits[c] > logits[maxIdx]) maxIdx = c
            if (maxIdx == targets[i]) correct++
        }
        return correct.toFloat() / seqs.size
    }

    fun saveToModelsDir(context: Context, modelName: String): File? {
        val dir = context.getExternalFilesDir("models") ?: return null
        dir.mkdirs()
        val file = File(dir, if (modelName.endsWith(".json")) modelName else "$modelName.json")
        return try { saveToFile(file); file } catch (e: Exception) {
            Log.e(TAG, "Failed to save LSTM model: ${e.message}", e); null
        }
    }

    private fun saveToFile(file: File) {
        val json = JSONObject()
        json.put("model_type", "lstm")
        json.put("feature_version", FeatureTransform.VERSION)
        json.put("num_features", numFeatures)
        json.put("seq_len", SEQ_LEN)
        json.put("hidden_size", hidden)
        json.put("pooling", "mean")
        json.put("val_accuracy", bestValAccuracy.toDouble())

        json.put("feature_mean", JSONArray().apply { featureMean.forEach { put(it.toDouble()) } })
        json.put("feature_std", JSONArray().apply { featureStd.forEach { put(it.toDouble()) } })
        restBaseline?.let { rb -> json.put("rest_baseline", JSONArray().apply { rb.forEach { put(it.toDouble()) } }) }
        val labelsArr = JSONArray().apply { classes.forEach { put(it) } }
        json.put("labels", labelsArr)
        json.put("classes", labelsArr)

        fun mat(w: Array<FloatArray>): JSONArray = JSONArray().apply {
            for (row in w) put(JSONArray().apply { for (v in row) put(v.toDouble()) })
        }
        fun vec(w: FloatArray): JSONArray = JSONArray().apply { for (v in w) put(v.toDouble()) }

        val weights = JSONObject()
        weights.put("lstm.weight_ih", mat(wIh))
        weights.put("lstm.weight_hh", mat(wHh))
        weights.put("lstm.bias", vec(bias))
        weights.put("linear.weight", mat(linW))
        weights.put("linear.bias", vec(linB))
        json.put("weights", weights)

        file.parentFile?.mkdirs()
        file.writeText(json.toString())
        Log.d(TAG, "Saved LSTM model to ${file.absolutePath}")
    }
}
