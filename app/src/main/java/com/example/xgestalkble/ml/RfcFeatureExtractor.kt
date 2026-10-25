package com.example.xgestalkble.ml

/**
 * Windowed feature extractor for the Random Forest classifier.
 *
 * The legacy RFC classified a single 21-value frame in isolation, so it was
 * blind to motion — it could never tell a moving gesture from a static pose
 * that happened to pass through the same instantaneous reading. This extractor
 * summarizes a short window of frames into per-feature statistics (mean, std,
 * min, max), so the forest sees how each channel *moves* over the window. std
 * and range encode motion; mean encodes pose.
 *
 * This object is the single source of truth for the windowed feature layout: it
 * is used identically at training time (sliding over CSV blocks) and at
 * inference time (sliding over live BLE frames), which is what keeps the two
 * from drifting apart. Mirrored by the web companion (rfc.js).
 */
object RfcFeatureExtractor {
    const val WINDOW = 15
    const val STATS_PER_FEATURE = 4 // mean, std, min, max

    fun outputSize(baseFeatures: Int): Int = baseFeatures * STATS_PER_FEATURE

    /**
     * Collapse a window of [window] raw frames into a flat statistics vector.
     * Layout is feature-major: [f0_mean, f0_std, f0_min, f0_max, f1_mean, ...].
     * [window] must be non-empty; every frame must have the same width.
     */
    fun extract(window: List<FloatArray>): FloatArray {
        val n = window.size
        val base = window[0].size
        val out = FloatArray(base * STATS_PER_FEATURE)
        for (b in 0 until base) {
            var sum = 0f
            var min = Float.POSITIVE_INFINITY
            var max = Float.NEGATIVE_INFINITY
            for (frame in window) {
                val v = frame[b]
                sum += v
                if (v < min) min = v
                if (v > max) max = v
            }
            val mean = sum / n
            var sq = 0f
            for (frame in window) {
                val d = frame[b] - mean
                sq += d * d
            }
            val std = kotlin.math.sqrt(sq / n)
            val o = b * STATS_PER_FEATURE
            out[o] = mean
            out[o + 1] = std
            out[o + 2] = min
            out[o + 3] = max
        }
        return out
    }

    /**
     * Slide a window over contiguous same-label blocks and split temporally,
     * so train and validation windows never overlap (mirrors the TCN
     * leakage-free split). Returns (trainX, trainY) and (valX, valY) of
     * already-windowed feature rows.
     *
     * NOTE: an earlier "stationary window" filter was removed here — on the
     * quantized −1/0/+1 accel data it discarded every window of static-pose
     * gestures, so those whole classes vanished from the trained model. Every
     * window is now kept; idle is handled by the dedicated REST class instead.
     */
    fun buildWindowedDatasetSplit(
        X: Array<FloatArray>,
        y: Array<String>,
        window: Int = WINDOW,
        testRatio: Float = 0.2f,
        sessionIds: Array<String>? = null
    ): Pair<Pair<Array<FloatArray>, Array<String>>, Pair<Array<FloatArray>, Array<String>>> {
        val trainX = mutableListOf<FloatArray>()
        val trainY = mutableListOf<String>()
        val valX = mutableListOf<FloatArray>()
        val valY = mutableListOf<String>()

        val total = X.size
        val stride = when {
            total > 30000 -> 4
            total > 15000 -> 2
            else -> 1
        }

        fun emitBlock(block: RecordingSessionSplitter.Block, validationOnly: Boolean?) {
            val blockSize = block.endExclusive - block.start
            if (blockSize < window) return
            val cut = (blockSize * (1f - testRatio)).toInt().coerceIn(window, blockSize)
            var s = 0
            while (s <= blockSize - window) {
                val frames = ArrayList<FloatArray>(window)
                for (t in 0 until window) frames.add(X[block.start + s + t])

                val feat = extract(frames)
                when (validationOnly) {
                    false -> {
                        trainX.add(feat); trainY.add(block.label)
                    }
                    true -> {
                        valX.add(feat); valY.add(block.label)
                    }
                    null -> if (s + window <= cut) {
                        trainX.add(feat); trainY.add(block.label)
                    } else if (s >= cut) {
                        valX.add(feat); valY.add(block.label)
                    }
                }
                s += stride
            }
        }

        if (total >= window) {
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
        }

        return Pair(
            Pair(trainX.toTypedArray(), trainY.toTypedArray()),
            Pair(valX.toTypedArray(), valY.toTypedArray())
        )
    }
}
