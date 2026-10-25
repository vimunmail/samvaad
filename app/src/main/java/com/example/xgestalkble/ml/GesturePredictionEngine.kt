package com.example.xgestalkble.ml

import com.example.xgestalkble.utils.GestureLabels
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shared, model-agnostic stabilization + segmentation for live gesture output.
 * Used by both the Live Prediction screen and the Conversation chat so they
 * behave identically.
 *
 * Pipeline per fresh inference:
 *   1. EMA-smooth the softmax probability vector and argmax THAT (anti-flicker).
 *   2. Margin gate: a class is "confident" only if top1 >= confFloor AND
 *      (top1 - top2) >= margin — rejects two-class ties.
 *   3. Rest detection: the model's REST/REST1/REST2… class, OR the feature vector
 *      staying unchanged (hand still) while no gesture is confidently seen.
 *   4. Commit logic — a gesture is emitted EXACTLY ONCE via whichever happens
 *      first:
 *        (a) Dwell commit: the SAME confident gesture is held steadily for
 *            commitHoldMs. This lets a mute signer chain signs continuously —
 *            hold a sign briefly → it fires → move to the next sign → it fires —
 *            without dropping the hand to a full rest between every sign. Holding
 *            a gesture through several inferences also filters transient
 *            misreads, so accuracy improves.
 *        (b) Rest-boundary commit: a sign too quick to reach the dwell time still
 *            commits its strongest label on the return to rest (legacy path).
 *      Either way: no flicker, and never more than one emission per sign.
 *
 * The caller does the raw inference (RFC window / TCN sequence) and feeds the
 * probability vector; the engine never touches the model.
 */
class GesturePredictionEngine {
    // ── Live-tunable knobs ──
    var emaAlpha = 0.35f      // probability smoothing
    var confFloor = 0.45f     // min top-1 probability
    var margin = 0.20f        // min (top1 - top2)
    var restHoldMs = 350L     // features still this long ⇒ at rest
    var minGestureMs = 250L   // ignore segments shorter than this
    var commitHoldMs = 450L   // hold one confident gesture this long ⇒ commit now
                              // (0 disables dwell commit → rest-boundary only)
    var idleRestMs = 1000L    // telemetry unchanged this long ⇒ HARD rest: force
                              // REST and block any gesture from starting, even if
                              // the model mis-fires on an idle hand. A real dynamic
                              // gesture always moves, so no motion ⇒ no gesture.
    var gyroDeadband = 40f    // dps jitter ignored in stillness check. CSV analysis
                              // shows REST gyro spikes to ±35 dps, so 40 covers all
                              // rest-noise and prevents false "motion detected" events.
    var accelDeadband = 1.01f // accel jitter ignored in stillness check. Firmware
                              // quantizes accel to discrete levels (5-level -2..+2),
                              // so idle accel is exactly constant and 0 (exact match)
                              // gives the cleanest rest detection.
    var minGyroEnergy = 50f   // minimum RMS gyro magnitude (dps) required to open a
                              // gesture segment. Real gestures produce gyro RMS well
                              // above 50; rest noise peaks at ~35. This gate prevents
                              // gyro noise during rest from triggering false gesture
                              // segments even if the deadband is narrowly exceeded.
    var commitCooldownMs = 400L // minimum time between gesture commits (ms)

    // STATIC-POSE MODE (for RFC static handshapes). When true, rest is taken
    // ONLY from the model's own REST class — never derived from the hand being
    // still. This lets a held static gesture keep predicting instead of
    // collapsing to REST, and disables the motion/gyro gates that exist for
    // dynamic TCN/LSTM gestures. Leave false for dynamic models.
    var staticMode = false

    // ── Debug readout (for tuning panels) ──
    var dbgTop1 = 0f; private set
    var dbgTop2 = 0f; private set
    var dbgTop1Label = "—"; private set

    private var pSmooth: FloatArray? = null
    private var prevFeatures: FloatArray? = null
    private var lastChangeMs = 0L
    private var active = false
    private var segmentStartMs = 0L
    private var bestLabel: String? = null
    private var bestConf = 0f
    // Dwell-commit bookkeeping: the gesture currently held steadily, when that
    // hold began, whether it is still eligible to fire, and whether a dwell
    // commit already fired in the current segment (so the rest-boundary path
    // won't repeat it).
    private var heldLabel: String? = null
    private var heldSinceMs = 0L
    private var armed = false
    private var dwellCommitted = false
    private var lastCommitTimeMs = 0L
    // Gyro energy: tracks whether the current motion is strong enough to be a
    // real gesture (as opposed to sensor noise during rest).
    private var currentGyroRms = 0f

    fun reset() {
        pSmooth = null
        prevFeatures = null
        lastChangeMs = 0L
        active = false
        segmentStartMs = 0L
        bestLabel = null
        bestConf = 0f
        heldLabel = null
        heldSinceMs = 0L
        armed = false
        dwellCommitted = false
        lastCommitTimeMs = 0L
        currentGyroRms = 0f
        dbgTop1 = 0f; dbgTop2 = 0f; dbgTop1Label = "—"
    }

    /**
     * @param liveLabel    current smoothed top-1 label (shown live, includes rest).
     * @param liveConf     its probability.
     * @param atRest       hand is in a rest/idle state right now.
     * @param committed    non-null EXACTLY once, on the return-to-rest that closes a
     *                     gesture segment — the single recognized gesture for that
     *                     cycle (never a rest label).
     * @param isConfident  true if the prediction passed both confFloor and margin gate.
     */
    data class Tick(
        val liveLabel: String,
        val liveConf: Float,
        val atRest: Boolean,
        val committed: String?,
        val isConfident: Boolean
    )

    /**
     * Feed one frame. Pass [probs] only on frames where a fresh inference ran
     * (e.g. every 3rd frame for the throttled TCN); pass null on the others — the
     * stillness clock still advances so rest is detected promptly.
     */
    fun update(
        nowMs: Long,
        features: FloatArray,
        probs: FloatArray?,
        classes: Array<String>
    ): Tick {
        // ── Stillness: did the (quantized) feature vector change? accel must match
        // exactly; gyro may jitter within a small deadband. ──
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

        // ── Gyro energy: RMS of the 3 gyro channels (features 18-20) ──
        // Real gestures produce high gyro energy (>> 50 dps); rest noise is < 40.
        // EMA smooth it so a single noisy frame can't trigger a false segment.
        if (features.size > 20) {
            val gx = features[18]; val gy = features[19]; val gz = features[20]
            val instantRms = sqrt(gx * gx + gy * gy + gz * gz)
            currentGyroRms = 0.3f * instantRms + 0.7f * currentGyroRms
        }

        // ── HARD IDLE GATE ──
        // If the telemetry has been unchanged for idleRestMs, the hand is idle.
        // A dynamic gesture always moves, so this is treated as ABSOLUTE rest: we
        // clear all smoothing / segment state and emit REST WITHOUT consulting the
        // model, so an idle hand can never produce a false gesture (and no stale,
        // high-confidence smoothed vector can re-fire the instant motion resumes).
        // The shorter restHoldMs override below still handles normal end-of-gesture
        // rest + commit; this only kicks in once the hand is genuinely idle.
        if (!staticMode && idleRestMs > 0L && stillForMs >= idleRestMs) {
            pSmooth = null
            active = false
            heldLabel = null
            armed = false
            bestLabel = null
            bestConf = 0f
            dwellCommitted = false
            dbgTop1 = 0f; dbgTop2 = 0f; dbgTop1Label = GestureLabels.REST
            return Tick(GestureLabels.REST, 1.0f, atRest = true, committed = null, isConfident = false)
        }

        // Update probability smoothing if a new prediction is available
        if (probs != null && probs.isNotEmpty()) {
            val sm = pSmooth
            if (sm == null || sm.size != probs.size) {
                pSmooth = probs.copyOf()
            } else {
                for (i in probs.indices) sm[i] = emaAlpha * probs[i] + (1f - emaAlpha) * sm[i]
            }
        }

        val ps = pSmooth
        if (ps == null || classes.isEmpty() || classes.size != ps.size) {
            // No prediction history yet, or classes mismatch — report rest purely from stillness.
            val atRestOnly = !staticMode && stillForMs >= restHoldMs
            return Tick(GestureLabels.REST, 1.0f, atRestOnly, null, false)
        }

        // Get top 1 and 2 from smoothed probabilities
        var i1 = 0
        for (i in 1 until ps.size) if (ps[i] > ps[i1]) i1 = i
        var i2 = -1
        for (i in ps.indices) if (i != i1 && (i2 < 0 || ps[i] > ps[i2])) i2 = i
        
        var l1 = classes[i1]
        var p1 = ps[i1]
        var p2 = if (i2 >= 0) ps[i2] else 0f

        // ── STILLNESS OVERRIDE (Glove at Rest) ──
        // If the glove is at rest (meaning telemetry is not changing for a clearance time),
        // we override the prediction to REST with 100% confidence.
        // Disabled in static mode — a held static gesture must keep predicting,
        // and rest there comes only from the model's REST class below.
        val isStillAtRest = !staticMode && stillForMs >= restHoldMs
        if (isStillAtRest) {
            val restClass = classes.firstOrNull { GestureLabels.isRest(it) } ?: GestureLabels.REST
            l1 = restClass
            p1 = 1.0f
            p2 = 0.0f
        }

        dbgTop1 = p1
        dbgTop2 = p2
        dbgTop1Label = l1

        // ── Margin gate ──
        val ok = p1 >= confFloor && (p1 - p2) >= margin
        val labelIsRest = GestureLabels.isRest(l1)

        // ── Rest detection ──
        // A confident rest class, OR stillness override.
        val atRest = (ok && labelIsRest) || isStillAtRest

        // ── Commit logic ──
        var committed: String? = null

        // Track how long the SAME confident gesture has been held continuously.
        var heldChanged = false
        if (ok && !labelIsRest) {
            if (l1 != heldLabel) {
                heldLabel = l1
                heldSinceMs = nowMs
                armed = true
                heldChanged = true
            }
        } else if (atRest) {
            heldLabel = null
            armed = false
        }

        if (!active) {
            // Open a segment on the first confident gesture after rest. For
            // dynamic models a gyro-energy floor rejects rest noise; static-pose
            // gestures have little/no motion, so that gate is skipped in static mode.
            val gyroOk = staticMode || currentGyroRms >= minGyroEnergy
            if (!atRest && ok && !labelIsRest && gyroOk) {
                active = true
                segmentStartMs = nowMs
                bestLabel = l1
                bestConf = p1
                dwellCommitted = false
            }
        } else {
            // Track the most confident label seen during the active segment (discrete signing).
            // Do not reset bestLabel/bestConf on heldChanged unless the new gesture has
            // higher confidence, preserving the true gesture against end-of-movement transition blips.
            if (ok && !labelIsRest && (bestLabel == null || p1 > bestConf)) {
                bestLabel = l1
                bestConf = p1
            }
            if (heldChanged) {
                dwellCommitted = false
            }

            // (4a) Dwell commit
            if (commitHoldMs > 0L && armed && heldLabel != null && !dwellCommitted &&
                nowMs - heldSinceMs >= commitHoldMs &&
                nowMs - segmentStartMs >= minGestureMs) {
                if (nowMs - lastCommitTimeMs >= commitCooldownMs) {
                    committed = heldLabel
                    lastCommitTimeMs = nowMs
                    pSmooth = null
                    armed = false
                    dwellCommitted = true
                }
            }

            // (4b) Rest-boundary commit
            if (atRest) {
                if (!dwellCommitted && bestLabel != null &&
                    nowMs - segmentStartMs >= minGestureMs) {
                    if (nowMs - lastCommitTimeMs >= commitCooldownMs) {
                        committed = bestLabel
                        lastCommitTimeMs = nowMs
                        pSmooth = null
                    }
                }
                active = false
                bestLabel = null
                bestConf = 0f
                dwellCommitted = false
            }
        }
        return Tick(liveLabel = l1, liveConf = p1, atRest = atRest, committed = committed, isConfident = ok)
    }
}
