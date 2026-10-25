package com.example.xgestalkble.ml

import com.example.xgestalkble.utils.GestureLabels
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SIGN → REST MODE recognizer for dynamic sequence models (TCN / LSTM).
 *
 * The streaming pipeline (GesturePredictionEngine) runs inference continuously
 * on a rolling 30-frame window, so mid-gesture windows — which contain only a
 * PART of the sign — are classified too. For multi-part Indian Sign Language
 * signs that hurts accuracy: the first half of one sign can look exactly like
 * a different sign.
 *
 * This recognizer instead treats each sign as one atomic unit:
 *
 *   1. WAITING — the hand is at rest. NO inference runs at all.
 *   2. Motion above the gyro-energy floor opens a segment → SIGNING. Every
 *      frame of the gesture is buffered; still NO inference runs, so nothing
 *      is (mis)predicted while the sign is in progress.
 *   3. The hand returning to rest (still for restHoldMs) closes the segment.
 *      The trailing rest frames are trimmed off and the COMPLETE gesture is
 *      classified exactly once:
 *        • long sign (≥ 30 frames): the model's probabilities are averaged
 *          over up to [maxWindows] raw-speed 30-frame windows spread across
 *          the whole sign — the same window shape it was trained on, but now
 *          covering the full motion instead of a random slice of it.
 *        • quick sign (< 30 frames): the segment is stretched to 30 frames by
 *          index-mapping so short brisk signs still classify.
 *   4. The top-1 label commits only if it clears confFloor + margin and is
 *      not a rest class. One sign → at most one commit.
 *
 * The caller supplies the model via the [update] classify lambda, so the same
 * recognizer drives both TCN and LSTM.
 */
class SegmentedGestureRecognizer {
    // ── Tunable knobs (mirror GesturePredictionEngine semantics) ──
    var restHoldMs = 350L        // features still this long ⇒ the sign ended
    var minGestureMs = 250L      // motion shorter than this is a blip, not a sign
    var confFloor = 0.55f        // min top-1 probability to commit
    var margin = 0.20f           // min (top1 - top2) to commit
    var gyroDeadband = 40f       // dps jitter ignored in the stillness check
    var accelDeadband = 1.01f    // quantized accel must match exactly at rest
    var minGyroEnergy = 50f      // RMS gyro (dps) needed to open a segment
    var seqLen = 30              // model window length (frames)
    var maxWindows = 5           // max raw windows averaged over a long sign
    var maxGestureFrames = 300   // hard cap ≈ 6 s @ 50 Hz — force-close runaways
    var minGestureFrames = 6     // segments shorter than this never classify
    var commitCooldownMs = 600L  // settle time after a commit before re-arming

    enum class Phase { WAITING, SIGNING }

    var phase = Phase.WAITING; private set
    // Debug readout: result of the LAST segment classification.
    var dbgTop1 = 0f; private set
    var dbgTop1Label = "—"; private set

    // Small look-back kept while WAITING so the gesture onset (which the
    // gyro-energy gate reacts to a few frames late) is not lost.
    private val preRoll = ArrayDeque<FloatArray>()
    private val preRollSize = 5

    private val buffer = ArrayList<FloatArray>()
    private var lastMotionIndex = -1
    private var prevFeatures: FloatArray? = null
    private var lastChangeMs = 0L
    private var segmentStartMs = 0L
    private var currentGyroRms = 0f
    private var lastCommitMs = 0L

    fun reset() {
        phase = Phase.WAITING
        preRoll.clear()
        buffer.clear()
        lastMotionIndex = -1
        prevFeatures = null
        lastChangeMs = 0L
        segmentStartMs = 0L
        currentGyroRms = 0f
        lastCommitMs = 0L
        dbgTop1 = 0f
        dbgTop1Label = "—"
    }

    /**
     * @param committed     non-null at most once per sign, on the return to rest.
     * @param committedConf the averaged top-1 probability of the committed sign.
     */
    data class Tick(
        val phase: Phase,
        val committed: String?,
        val committedConf: Float,
        val framesCaptured: Int
    )

    /**
     * Feed one drift-compensated feature frame. [classify] is called ONLY at
     * the rest boundary (never while the sign is in progress) with sequences
     * of exactly [seqLen] frames.
     */
    fun update(
        nowMs: Long,
        features: FloatArray,
        classify: (List<FloatArray>) -> FloatArray?,
        classes: Array<String>
    ): Tick {
        // Stillness: same quantized-feature check as GesturePredictionEngine —
        // accel must match exactly, gyro may jitter within the deadband.
        val prev = prevFeatures
        var changed = false
        if (prev == null || prev.size != features.size) {
            changed = true
        } else {
            for (i in features.indices) {
                val tol = if (i >= 18) gyroDeadband else accelDeadband
                if (abs(features[i] - prev[i]) > tol) { changed = true; break }
            }
        }
        prevFeatures = features.copyOf()
        if (changed) lastChangeMs = nowMs
        val stillForMs = nowMs - lastChangeMs

        // Smoothed gyro RMS (channels 18-20): real signs sit well above the
        // rest-noise ceiling, so this gates segment opening.
        if (features.size > 20) {
            val gx = features[18]; val gy = features[19]; val gz = features[20]
            val instantRms = sqrt(gx * gx + gy * gy + gz * gz)
            currentGyroRms = 0.3f * instantRms + 0.7f * currentGyroRms
        }

        when (phase) {
            Phase.WAITING -> {
                preRoll.addLast(features.copyOf())
                while (preRoll.size > preRollSize) preRoll.removeFirst()
                if (changed && currentGyroRms >= minGyroEnergy &&
                    nowMs - lastCommitMs >= commitCooldownMs) {
                    phase = Phase.SIGNING
                    segmentStartMs = nowMs
                    buffer.clear()
                    buffer.addAll(preRoll)
                    preRoll.clear()
                    lastMotionIndex = buffer.size - 1
                }
            }
            Phase.SIGNING -> {
                if (buffer.size < maxGestureFrames) {
                    buffer.add(features.copyOf())
                    if (changed) lastMotionIndex = buffer.size - 1
                }
                val ended = stillForMs >= restHoldMs || buffer.size >= maxGestureFrames
                if (ended) {
                    var committed: String? = null
                    var committedConf = 0f
                    // Trim the trailing still frames — the sign is [0..lastMotionIndex].
                    val motionMs = lastChangeMs - segmentStartMs
                    val segment: List<FloatArray> =
                        if (lastMotionIndex >= 0) buffer.subList(0, lastMotionIndex + 1) else emptyList()
                    if (segment.size >= minGestureFrames && motionMs >= minGestureMs) {
                        val probs = classifySegment(segment, classify)
                        if (probs != null && probs.size == classes.size && probs.isNotEmpty()) {
                            var i1 = 0
                            for (i in 1 until probs.size) if (probs[i] > probs[i1]) i1 = i
                            var i2 = -1
                            for (i in probs.indices) if (i != i1 && (i2 < 0 || probs[i] > probs[i2])) i2 = i
                            val l1 = classes[i1].uppercase()
                            val p1 = probs[i1]
                            val p2 = if (i2 >= 0) probs[i2] else 0f
                            dbgTop1 = p1
                            dbgTop1Label = l1
                            if (!GestureLabels.isRest(l1) && p1 >= confFloor && (p1 - p2) >= margin) {
                                committed = l1
                                committedConf = p1
                                lastCommitMs = nowMs
                            }
                        }
                    }
                    buffer.clear()
                    lastMotionIndex = -1
                    phase = Phase.WAITING
                    return Tick(phase, committed, committedConf, 0)
                }
            }
        }
        return Tick(phase, null, 0f, buffer.size)
    }

    private fun classifySegment(
        segment: List<FloatArray>,
        classify: (List<FloatArray>) -> FloatArray?
    ): FloatArray? {
        if (segment.size < seqLen) {
            // Quick sign: stretch to the model's window length by index-mapping.
            val n = segment.size
            val stretched = ArrayList<FloatArray>(seqLen)
            for (i in 0 until seqLen) {
                val idx = if (seqLen == 1) 0 else (i.toLong() * (n - 1) / (seqLen - 1)).toInt()
                stretched.add(segment[idx])
            }
            return classify(stretched)
        }
        // Long sign: average probabilities over up to maxWindows raw-speed
        // windows spread evenly from the start of the sign to its end.
        val lastOffset = segment.size - seqLen
        val k = minOf(maxWindows, lastOffset + 1)
        var acc: FloatArray? = null
        var count = 0
        for (w in 0 until k) {
            val off = if (k == 1) 0 else (w.toLong() * lastOffset / (k - 1)).toInt()
            val probs = classify(segment.subList(off, off + seqLen)) ?: continue
            if (acc == null) acc = FloatArray(probs.size)
            if (probs.size != acc.size) continue
            for (i in probs.indices) acc[i] += probs[i]
            count++
        }
        val out = acc ?: return null
        if (count == 0) return null
        for (i in out.indices) out[i] /= count
        return out
    }
}
