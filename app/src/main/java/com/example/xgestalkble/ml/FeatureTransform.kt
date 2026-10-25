package com.example.xgestalkble.ml

/**
 * Feature transform applied identically at training time (on CSV rows) and at
 * inference time (on live BLE frames). Mirrored by the web companion so model
 * JSONs stay interchangeable.
 *
 * SAMVAAD v3.1 firmware already emits session-invariant features:
 *   - finger / wrist accel are gravity-referenced QUANTIZED LEVELS (-2..+2),
 *     reproducible across power cycles via the accel-based calibration profile
 *   - wrist gyro is body-frame integer deg/s (rotation rates are invariant)
 * There is nothing session-dependent left to strip, so the transform is an
 * identity pass-through. The TCN still z-score standardizes on top of this
 * (gyro dps and accel levels live on different scales).
 *
 * Feature layout (21): see DataNormalizer.
 *   [0-14]  finger accel levels (thumb..little, x/y/z)
 *   [15-17] wrist accel levels (x/y/z)
 *   [18-20] wrist gyro deg/s (x/y/z)
 */
object FeatureTransform {
    const val NUM_FEATURES = 21
    const val VERSION = 5

    /** Identity pass-through (v3.1 features are already invariant). */
    fun apply(raw: FloatArray): FloatArray = raw.copyOf()

    /**
     * Compute per-feature mean and standard deviation over a set of
     * (already transformed) sequence frames. Constant features get std=1
     * so standardization maps them to exactly 0.
     */
    fun computeStats(sequences: List<Array<FloatArray>>): Pair<FloatArray, FloatArray> {
        val mean = FloatArray(NUM_FEATURES)
        val std = FloatArray(NUM_FEATURES)
        var count = 0L
        for (seq in sequences) {
            for (frame in seq) {
                for (f in 0 until NUM_FEATURES) mean[f] += frame[f]
                count++
            }
        }
        if (count == 0L) return Pair(mean, FloatArray(NUM_FEATURES) { 1f })
        for (f in 0 until NUM_FEATURES) mean[f] /= count

        for (seq in sequences) {
            for (frame in seq) {
                for (f in 0 until NUM_FEATURES) {
                    val d = frame[f] - mean[f]
                    std[f] += d * d
                }
            }
        }
        for (f in 0 until NUM_FEATURES) {
            std[f] = kotlin.math.sqrt(std[f] / count)
            if (std[f] < 1e-4f) std[f] = 1f
        }
        return Pair(mean, std)
    }

    fun standardize(frame: FloatArray, mean: FloatArray, std: FloatArray): FloatArray {
        return FloatArray(frame.size) { f -> (frame[f] - mean[f]) / std[f] }
    }

    /**
     * Per-channel mean over every REST frame, used as the model's session anchor
     * (`rest_baseline`) for runtime drift compensation — see SessionCalibrator.
     * Returns null when the dataset has no REST frames (compensation then stays
     * disabled, exactly like a legacy model). Raw levels in, raw levels out — the
     * transform is identity, so this is the mean the live signal is re-anchored to.
     */
    fun restBaselineFromSequences(
        sequences: List<Array<FloatArray>>,
        labels: List<String>
    ): FloatArray? {
        val mean = FloatArray(NUM_FEATURES)
        var count = 0L
        for (s in sequences.indices) {
            if (!com.example.xgestalkble.utils.GestureLabels.isRest(labels[s])) continue
            for (frame in sequences[s]) {
                for (f in 0 until NUM_FEATURES) mean[f] += frame[f]
                count++
            }
        }
        if (count == 0L) return null
        for (f in 0 until NUM_FEATURES) mean[f] /= count
        return mean
    }

    /** Per-channel mean over every REST row (per-frame models like RFC). */
    fun restBaselineFromRows(X: Array<FloatArray>, y: Array<String>): FloatArray? {
        val mean = FloatArray(NUM_FEATURES)
        var count = 0L
        for (i in X.indices) {
            if (!com.example.xgestalkble.utils.GestureLabels.isRest(y[i])) continue
            val row = X[i]
            for (f in 0 until NUM_FEATURES) mean[f] += row[f]
            count++
        }
        if (count == 0L) return null
        for (f in 0 until NUM_FEATURES) mean[f] /= count
        return mean
    }
}
