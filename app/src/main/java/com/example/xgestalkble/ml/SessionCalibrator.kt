package com.example.xgestalkble.ml

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runtime per-session drift compensation — the fix for "predictions are wrong
 * after the glove is powered off and back on".
 *
 * The firmware re-derives its gravity / finger calibration baseline on every
 * power cycle, so the SAME hand pose lands in slightly different quantized accel
 * buckets from boot to boot. A model trained in one power session then
 * mis-predicts after the glove is restarted, even though the gesture itself
 * hasn't changed. This class re-anchors each live session to the reference the
 * model was trained on:
 *
 *   1. While the hand is still (low gyro energy + unchanging accel) it learns the
 *      live REST baseline of the 18 accel channels (a slow EMA over still frames).
 *   2. It subtracts (liveRest − modelRest) from every accel channel before the
 *      features reach the classifier, so what the model sees is a pose's
 *      DEVIATION from rest — which is session-invariant — not the drifting
 *      absolute level.
 *
 * Gyro channels (18..20) are rotation rates, already ~zero at rest, so they are
 * never offset. When the model carries no rest_baseline (legacy models) or no
 * live baseline has been learned yet, [process] is a pass-through, so old models
 * keep working exactly as before.
 *
 * One instance per live screen; feed every incoming frame through [process].
 */
class SessionCalibrator {
    companion object {
        private const val NUM = 21
        private const val ACCEL = 18          // channels 0..17 are accel levels
        private const val GYRO_STILL = 45f    // gyro RMS (dps) below this ⇒ hand still
        private const val ACCEL_TOL = 1.01f   // accel "unchanged" within one quant level
        private const val LEARN_ALPHA = 0.05f // EMA weight for the live rest baseline
        private const val MIN_STILL_FRAMES = 10  // ~0.2 s at 50 Hz before we trust rest
        // A still frame only updates the baseline if every accel channel is within
        // this many levels of the model's rest reference. Without it, a held static
        // pose (which is also "still") would be mistaken for rest and mis-anchor the
        // session. Boot drift is ≲1 level, so 2.5 admits genuine rest yet rejects an
        // actual handshape (which deviates further from rest by design).
        private const val REST_NEAR = 2.5f
    }

    // The model's reference rest baseline (21 ch, accel meaningful), or null for
    // legacy models without a rest_baseline.
    private var modelRest: FloatArray? = null
    // Live rest baseline (18 accel ch); null until enough still frames are seen.
    private var liveRest: FloatArray? = null
    private var prev: FloatArray? = null
    private var stillFrames = 0

    /** True once a live rest baseline is learned AND the model carries a reference. */
    val isCalibrated: Boolean get() = modelRest != null && liveRest != null

    /** How many consecutive still frames have been observed (0 = moving). */
    val stillCount: Int get() = stillFrames

    /**
     * Set (or clear) the active model's rest baseline and start a fresh session.
     * Pass the loaded model's [restBaseline]; null disables compensation.
     */
    fun setReference(baseline: FloatArray?) {
        modelRest = baseline?.copyOf()
        liveRest = null
        prev = null
        stillFrames = 0
    }

    /** Forget the learned live baseline (e.g. on disconnect) but keep the reference. */
    fun resetSession() {
        liveRest = null
        prev = null
        stillFrames = 0
    }

    /**
     * Update the live rest baseline from [raw] when the hand is still, then return
     * the drift-compensated feature vector. Never mutates [raw]; returns [raw]
     * unchanged when there is nothing to anchor to (no reference / not yet learned).
     */
    fun process(raw: FloatArray): FloatArray {
        if (raw.size < NUM) return raw

        // ── Stillness: accel unchanged within tolerance AND low gyro energy. ──
        val p = prev
        var accelStill = p != null && p.size == raw.size
        if (accelStill) {
            for (i in 0 until ACCEL) {
                if (abs(raw[i] - p!![i]) > ACCEL_TOL) { accelStill = false; break }
            }
        }
        prev = raw.copyOf()

        val gyroRms = sqrt(raw[18] * raw[18] + raw[19] * raw[19] + raw[20] * raw[20])
        val still = accelStill && gyroRms < GYRO_STILL

        val mr = modelRest

        // A still frame is only a REST sample if it also sits near the model's rest
        // reference — otherwise a held static pose would mis-anchor the session.
        var restLike = mr != null
        if (restLike) {
            for (i in 0 until ACCEL) {
                if (abs(raw[i] - mr!![i]) > REST_NEAR) { restLike = false; break }
            }
        }

        if (still) {
            stillFrames++
            if (stillFrames >= MIN_STILL_FRAMES && restLike) {
                val lr = liveRest ?: FloatArray(ACCEL) { raw[it] }.also { liveRest = it }
                for (i in 0 until ACCEL) lr[i] = (1f - LEARN_ALPHA) * lr[i] + LEARN_ALPHA * raw[i]
            }
        } else {
            stillFrames = 0
        }

        val lr = liveRest
        if (mr == null || lr == null) return raw

        // Subtract the per-channel session offset from the accel channels only.
        val out = raw.copyOf()
        for (i in 0 until ACCEL) out[i] = raw[i] - (lr[i] - mr[i])
        return out
    }
}
