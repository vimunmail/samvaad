package com.example.xgestalkble.ml

import android.content.Context
import com.example.xgestalkble.service.TrainingService
import com.example.xgestalkble.utils.RecordingModelType
import com.example.xgestalkble.utils.GestureTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Process-wide owner of on-device model training.
 *
 * Training used to run inside the Compose screens' coroutine scopes, which died
 * with the screen (and the whole run died with the Activity). This singleton
 * runs the exact same RFC / TCN / LSTM pipelines on its own [CoroutineScope]
 * and mirrors progress into [StateFlow]s, so:
 *   - the UI (DataCollectionScreen / TeachGesturesScreen) just collects state,
 *   - [TrainingService] keeps the process alive as a foreground service and
 *     renders the same state into a progress notification,
 *   - closing the app mid-training no longer aborts the run.
 */
object TrainingManager {

    /** Terminal outcome of the last training run (null while none finished). */
    data class Result(
        val success: Boolean,
        val accuracy: Float = 0f,
        val message: String = "",
        // True when the run was started from the guided "Teach My Signs" flow;
        // that screen only surfaces results from its own runs.
        val easyMode: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isTraining = MutableStateFlow(false)
    val isTraining: StateFlow<Boolean> = _isTraining.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _statusText = MutableStateFlow("Engine Offline. Waiting to Train...")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _lastResult = MutableStateFlow<Result?>(null)
    val lastResult: StateFlow<Result?> = _lastResult.asStateFlow()

    /** Title line for the training notification, set when a run starts. */
    @Volatile
    var notificationTitle: String = "Training model"
        private set

    private fun log(line: String) {
        _logs.value = _logs.value + line
    }

    private fun fail(status: String, easyMode: Boolean): Result {
        _statusText.value = status
        return Result(success = false, message = status, easyMode = easyMode)
    }

    /** Advanced screen entry point. [hyperParam] is trees for RFC, epochs for TCN/LSTM. */
    fun startAdvanced(
        context: Context,
        type: RecordingModelType,
        csvFileName: String,
        outputModelName: String,
        hyperParam: Int?
    ) {
        if (!_isTraining.compareAndSet(expect = false, update = true)) return
        val appContext = context.applicationContext
        notificationTitle = when (type) {
            RecordingModelType.RFC -> "Training Random Forest model"
            RecordingModelType.TCN -> "Training TCN model"
            RecordingModelType.LSTM -> "Training LSTM model"
        }
        beginRun(appContext)
        scope.launch {
            val result = try {
                when (type) {
                    RecordingModelType.RFC ->
                        runRfc(appContext, csvFileName, hyperParam ?: 200, outputModelName)
                    RecordingModelType.TCN ->
                        runTcn(appContext, csvFileName, hyperParam ?: 60, outputModelName)
                    RecordingModelType.LSTM ->
                        runLstm(appContext, csvFileName, hyperParam ?: 60, outputModelName)
                }
            } catch (t: Throwable) {
                log("[ERR] Unexpected failure: ${t.message}")
                fail("Training failed: ${t.message}", easyMode = false)
            }
            finishRun(result)
        }
    }

    /** "Teach My Signs" entry point — fixed LSTM recipe with friendly messages. */
    fun startEasy(context: Context, csvFileName: String, outputModelName: String, epochs: Int) {
        if (!_isTraining.compareAndSet(expect = false, update = true)) return
        val appContext = context.applicationContext
        notificationTitle = "Teaching the glove your signs"
        beginRun(appContext)
        scope.launch {
            val result = try {
                runEasyLstm(appContext, csvFileName, outputModelName, epochs)
            } catch (t: Throwable) {
                fail("Something went wrong: ${t.message}", easyMode = true)
            }
            finishRun(result)
        }
    }

    private fun beginRun(appContext: Context) {
        _progress.value = 0f
        _logs.value = emptyList()
        _lastResult.value = null
        TrainingService.start(appContext)
    }

    private fun finishRun(result: Result) {
        _lastResult.value = result
        _isTraining.value = false
    }

    // ── RFC pipeline ─────────────────────────────────────────────────────────

    private suspend fun runRfc(
        context: Context,
        csvFileName: String,
        nEstimators: Int,
        outputModelName: String
    ): Result {
        _statusText.value = "Loading dataset..."
        _progress.value = 0f
        log("[SYS] Loading CSV: $csvFileName")

        val dataset = withContext(Dispatchers.IO) {
            CsvDatasetParser.parseFromTelemetryDir(context, csvFileName)
        }
        if (dataset == null) {
            log("[ERR] Failed to parse CSV file!")
            return fail("Training failed: Could not parse CSV", easyMode = false)
        }

        log("[SYS] Loaded ${dataset.X.size} samples, ${dataset.labelCounts.size} classes")
        dataset.labelCounts.forEach { (label, count) ->
            log("[SYS]   $label: $count samples")
        }
        _progress.value = 0.1f

        if (dataset.X.size < 10) {
            log("[ERR] Need at least 10 samples to train. Record more gestures first.")
            return fail("Training failed: Not enough samples", easyMode = false)
        }
        if (dataset.labelCounts.size < 2) {
            log("[ERR] Need at least 2 different gesture labels to train a classifier.")
            return fail("Training failed: Need multiple labels", easyMode = false)
        }

        _statusText.value = "Building windowed features (W=${RfcFeatureExtractor.WINDOW}, leakage-free split)..."
        log("[SYS] Building windowed RFC features over ${RfcFeatureExtractor.WINDOW}-frame windows (mean/std/min/max per channel)")
        val (trainPair, valPair) = withContext(Dispatchers.Default) {
            RfcFeatureExtractor.buildWindowedDatasetSplit(
                dataset.X,
                dataset.y,
                RfcFeatureExtractor.WINDOW,
                0.2f,
                dataset.sessionIds
            )
        }
        val trainXw = trainPair.first
        val trainYw = trainPair.second
        val valXw = valPair.first
        val valYw = valPair.second

        if (trainXw.isEmpty()) {
            log("[ERR] No windows extracted! Each gesture block needs at least ${RfcFeatureExtractor.WINDOW} continuous samples. Record more data first.")
            return fail("Training failed: Not enough samples per gesture", easyMode = false)
        }

        log("[SYS] Train: ${trainXw.size} windows, Val: ${valXw.size} windows (${trainXw.firstOrNull()?.size ?: 0} features each)")
        _progress.value = 0.2f

        _statusText.value = "Training Random Forest ($nEstimators trees)..."
        log("[SYS] Fitting RandomForestClassifier(n_estimators=$nEstimators, windowed=true)...")

        val clf = RandomForestClassifier(
            nEstimators = nEstimators,
            maxDepth = 24,
            randomSeed = 42L
        )
        clf.windowed = true
        clf.window = RfcFeatureExtractor.WINDOW
        // Session anchor for runtime drift compensation: raw 21-ch REST mean.
        // The forest trains on windowed stats, so it can't derive this itself.
        clf.restBaseline = FeatureTransform.restBaselineFromRows(dataset.X, dataset.y)

        val startTime = System.currentTimeMillis()
        withContext(Dispatchers.Default) {
            clf.fit(trainXw, trainYw)
        }
        val trainTimeMs = System.currentTimeMillis() - startTime
        _progress.value = 0.7f

        log("[SYS] Training completed in ${trainTimeMs}ms")
        log("[SYS] Classes: ${clf.classes.joinToString(", ")}")

        _statusText.value = "Evaluating model accuracy..."
        val accuracy = if (valXw.isNotEmpty()) withContext(Dispatchers.Default) {
            clf.score(valXw, valYw)
        } else 0f
        _progress.value = 0.85f

        log("[SYS] Test Accuracy: ${String.format(Locale.US, "%.2f", accuracy * 100)}%")

        _statusText.value = "Saving model..."
        val modelFileName = "${outputModelName}_rfc.json"
        val savedFile = withContext(Dispatchers.IO) {
            clf.saveToModelsDir(context, modelFileName)
        }

        return if (savedFile != null) {
            GestureTranslator.ensureLoaded(context)
            GestureTranslator.registerModelClasses(clf.classes.toList())
            log("[SYS] Model saved: models/${savedFile.name} (${savedFile.length()} bytes)")
            _progress.value = 1.0f
            val status = "Training Complete! Accuracy: ${String.format(Locale.US, "%.1f", accuracy * 100)}%"
            _statusText.value = status
            Result(success = true, accuracy = accuracy, message = status)
        } else {
            log("[ERR] Failed to save model file!")
            fail("Training completed but save failed", easyMode = false)
        }
    }

    // ── TCN pipeline ─────────────────────────────────────────────────────────

    private suspend fun runTcn(
        context: Context,
        csvFileName: String,
        epochs: Int,
        outputModelName: String
    ): Result {
        val prep = prepareSequences(csvFileName, context) ?: return _lastPrepFailure
        val (trainSequences, trainLabels, valSequences, valLabels) = prep

        _statusText.value = "Training Temporal Convolutional Network..."
        log("[SYS] Fitting TcnModel(epochs=$epochs)...")

        val uniqueClasses = trainLabels.toSet().sorted().toTypedArray()
        val trainer = TcnTrainer(uniqueClasses)

        val startTime = System.currentTimeMillis()
        withContext(Dispatchers.Default) {
            trainer.fit(trainSequences, trainLabels, valSequences, valLabels, epochs) { epoch, loss, valAcc ->
                // StateFlow writes are thread-safe; no main-thread hop needed.
                _progress.value = 0.35f + (0.5f * (epoch.toFloat() / epochs))
                _statusText.value = "Epoch $epoch/$epochs - Loss: ${String.format(Locale.US, "%.4f", loss)} - Val Acc: ${String.format(Locale.US, "%.1f", valAcc * 100)}%"
                log("[EPOCH $epoch] Loss: ${String.format(Locale.US, "%.4f", loss)}, Val Acc: ${String.format(Locale.US, "%.1f", valAcc * 100)}%")
            }
        }
        val trainTimeMs = System.currentTimeMillis() - startTime
        _progress.value = 0.9f
        val bestAcc = trainer.bestValAccuracy
        log("[SYS] TCN training completed in ${trainTimeMs}ms")
        log("[SYS] Best val accuracy: ${String.format(Locale.US, "%.1f", bestAcc * 100)}% (best-checkpoint weights saved)")

        _statusText.value = "Saving TCN model..."
        val modelFileName = "${outputModelName}_tcn.json"
        val savedFile = withContext(Dispatchers.IO) {
            trainer.saveToModelsDir(context, modelFileName)
        }

        return if (savedFile != null) {
            GestureTranslator.ensureLoaded(context)
            GestureTranslator.registerModelClasses(trainer.classes.toList())
            log("[SYS] TCN Model saved: models/${savedFile.name} (${savedFile.length()} bytes)")
            _progress.value = 1.0f
            val status = "TCN Training Complete! Best Val Accuracy: ${String.format(Locale.US, "%.1f", bestAcc * 100)}%"
            _statusText.value = status
            Result(success = true, accuracy = bestAcc, message = status)
        } else {
            log("[ERR] Failed to save TCN model file!")
            fail("TCN Training completed but save failed", easyMode = false)
        }
    }

    // ── LSTM pipeline (advanced) ─────────────────────────────────────────────

    private suspend fun runLstm(
        context: Context,
        csvFileName: String,
        epochs: Int,
        outputModelName: String
    ): Result {
        val prep = prepareSequences(csvFileName, context) ?: return _lastPrepFailure
        val (trainSequences, trainLabels, valSequences, valLabels) = prep

        _statusText.value = "Training LSTM Network..."
        log("[SYS] Fitting LstmModel(hidden=${LstmTrainer.HIDDEN}, epochs=$epochs)...")

        val uniqueClasses = trainLabels.toSet().sorted().toTypedArray()
        val trainer = LstmTrainer(uniqueClasses)

        val startTime = System.currentTimeMillis()
        withContext(Dispatchers.Default) {
            trainer.fit(trainSequences, trainLabels, valSequences, valLabels, epochs) { epoch, loss, valAcc ->
                _progress.value = 0.35f + (0.5f * (epoch.toFloat() / epochs))
                _statusText.value = "Epoch $epoch/$epochs - Loss: ${String.format(Locale.US, "%.4f", loss)} - Val Acc: ${String.format(Locale.US, "%.1f", valAcc * 100)}%"
                log("[EPOCH $epoch] Loss: ${String.format(Locale.US, "%.4f", loss)}, Val Acc: ${String.format(Locale.US, "%.1f", valAcc * 100)}%")
            }
        }
        val trainTimeMs = System.currentTimeMillis() - startTime
        _progress.value = 0.9f
        val bestAcc = trainer.bestValAccuracy
        log("[SYS] LSTM training completed in ${trainTimeMs}ms")
        log("[SYS] Best val accuracy: ${String.format(Locale.US, "%.1f", bestAcc * 100)}% (best-checkpoint weights saved)")

        _statusText.value = "Saving LSTM model..."
        val modelFileName = "${outputModelName}_lstm.json"
        val savedFile = withContext(Dispatchers.IO) {
            trainer.saveToModelsDir(context, modelFileName)
        }

        return if (savedFile != null) {
            GestureTranslator.ensureLoaded(context)
            GestureTranslator.registerModelClasses(uniqueClasses.toList())
            log("[SYS] LSTM Model saved: models/${savedFile.name} (${savedFile.length()} bytes)")
            _progress.value = 1.0f
            val status = "LSTM Training Complete! Best Val Accuracy: ${String.format(Locale.US, "%.1f", bestAcc * 100)}%"
            _statusText.value = status
            Result(success = true, accuracy = bestAcc, message = status)
        } else {
            log("[ERR] Failed to save LSTM model file!")
            fail("LSTM Training completed but save failed", easyMode = false)
        }
    }

    // Shared dataset-load + sequence-window step for TCN/LSTM. Returns null on
    // failure, with the failure Result stashed in [_lastPrepFailure].
    private data class SequenceData(
        val trainSequences: List<Array<FloatArray>>,
        val trainLabels: List<String>,
        val valSequences: List<Array<FloatArray>>,
        val valLabels: List<String>
    )

    @Volatile
    private var _lastPrepFailure: Result = Result(success = false, message = "Training failed")

    private suspend fun prepareSequences(csvFileName: String, context: Context): SequenceData? {
        _statusText.value = "Loading dataset..."
        _progress.value = 0f
        log("[SYS] Loading CSV: $csvFileName")

        val dataset = withContext(Dispatchers.IO) {
            CsvDatasetParser.parseFromTelemetryDir(context, csvFileName)
        }
        if (dataset == null) {
            log("[ERR] Failed to parse CSV file!")
            _lastPrepFailure = fail("Training failed: Could not parse CSV", easyMode = false)
            return null
        }

        log("[SYS] Loaded ${dataset.X.size} samples, ${dataset.labelCounts.size} classes")
        dataset.labelCounts.forEach { (label, count) ->
            log("[SYS]   $label: $count samples")
        }
        _progress.value = 0.1f

        if (dataset.X.size < 40) {
            log("[ERR] Need at least 40 samples to build sequence windows. Record more data first.")
            _lastPrepFailure = fail("Training failed: Not enough samples", easyMode = false)
            return null
        }
        if (dataset.labelCounts.size < 2) {
            log("[ERR] Need at least 2 different gesture labels to train.")
            _lastPrepFailure = fail("Training failed: Need multiple labels", easyMode = false)
            return null
        }

        _statusText.value = "Building sequences (seq_len=30, leakage-free split)..."
        // Window + split in one pass so train/val windows never overlap
        // (no near-duplicate windows leaking across the split).
        val splitResult = withContext(Dispatchers.Default) {
            TcnTrainer.createTcnSequencesSplit(dataset.X, dataset.y, 30, 0.2f, dataset.sessionIds)
        }
        val trainSequences = splitResult.first.first
        val trainLabels = splitResult.first.second
        val valSequences = splitResult.second.first
        val valLabels = splitResult.second.second

        if (trainSequences.isEmpty()) {
            log("[ERR] No sequences extracted! Make sure you have recorded continuous gestures of the same label.")
            _lastPrepFailure = fail("Training failed: No sequences extracted", easyMode = false)
            return null
        }

        log("[SYS] Extracted ${trainSequences.size + valSequences.size} sequences (leakage-free).")
        _progress.value = 0.25f
        log("[SYS] Train: ${trainSequences.size} sequences, Val: ${valSequences.size} sequences")
        _progress.value = 0.35f

        return SequenceData(trainSequences, trainLabels, valSequences, valLabels)
    }

    // ── Easy "Teach My Signs" pipeline (fixed LSTM recipe) ───────────────────

    private suspend fun runEasyLstm(
        context: Context,
        csvFileName: String,
        outputModelName: String,
        epochs: Int
    ): Result {
        _progress.value = 0.05f
        _statusText.value = "📂 Reading your recordings…"

        val dataset = withContext(Dispatchers.IO) {
            CsvDatasetParser.parseFromTelemetryDir(context, csvFileName)
        }
        if (dataset == null) {
            return fail("Could not read your recordings. Record your signs again.", easyMode = true)
        }
        if (dataset.labelCounts.size < 2) {
            return fail("Record the ✋ resting hand and at least 1 sign first.", easyMode = true)
        }
        if (dataset.X.size < 40) {
            return fail("Not enough recordings yet. Record more for each sign.", easyMode = true)
        }

        _progress.value = 0.15f
        _statusText.value = "✂️ Preparing your signs…"
        val splitResult = withContext(Dispatchers.Default) {
            TcnTrainer.createTcnSequencesSplit(dataset.X, dataset.y, 30, 0.2f, dataset.sessionIds)
        }
        val trainSequences = splitResult.first.first
        val trainLabels = splitResult.first.second
        val valSequences = splitResult.second.first
        val valLabels = splitResult.second.second
        if (trainSequences.isEmpty()) {
            return fail("Recordings are too short. Record full 5-second takes.", easyMode = true)
        }

        _progress.value = 0.2f
        _statusText.value = "🧠 Learning your signs…"
        val uniqueClasses = trainLabels.toSet().sorted().toTypedArray()
        val trainer = LstmTrainer(uniqueClasses)
        withContext(Dispatchers.Default) {
            trainer.fit(trainSequences, trainLabels, valSequences, valLabels, epochs) { epoch, _, _ ->
                _progress.value = 0.2f + 0.7f * (epoch.toFloat() / epochs)
                _statusText.value = "🧠 Learning your signs… ($epoch of $epochs)"
            }
        }

        _progress.value = 0.95f
        _statusText.value = "💾 Saving…"
        val savedFile = withContext(Dispatchers.IO) {
            trainer.saveToModelsDir(context, "${outputModelName}_lstm.json")
        }
        if (savedFile == null) {
            return fail("Could not save. Free up some phone storage and try again.", easyMode = true)
        }
        GestureTranslator.ensureLoaded(context)
        GestureTranslator.registerModelClasses(trainer.classes.toList())

        _progress.value = 1f
        _statusText.value = "✅ Done!"
        val pct = String.format(Locale.US, "%.0f", trainer.bestValAccuracy * 100)
        return Result(
            success = true,
            accuracy = trainer.bestValAccuracy,
            message = "Your glove learned your signs — $pct% accuracy.",
            easyMode = true
        )
    }
}
