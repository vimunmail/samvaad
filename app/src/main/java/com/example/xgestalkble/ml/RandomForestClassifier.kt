package com.example.xgestalkble.ml

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Pure Kotlin Random Forest Classifier for on-device gesture recognition.
 * Matches the webapp's sklearn RandomForestClassifier logic:
 *   - Gini impurity for splits
 *   - sqrt(n_features) random feature subset per split
 *   - Majority-vote ensemble prediction
 *   - CSV layout: Label (col 0), 21 features (cols 1-21)
 */
class RandomForestClassifier(
    private val nEstimators: Int = 50,
    private val maxDepth: Int = 16,
    private val minSamplesSplit: Int = 2,
    private val minSamplesLeaf: Int = 1,
    private val randomSeed: Long = 42L
) {
    companion object {
        private const val TAG = "RFC"

        /**
         * Load a trained model from a JSON file.
         */
        fun loadFromFile(file: File): RandomForestClassifier {
            val json = JSONObject(file.readText())
            val clf = RandomForestClassifier(
                nEstimators = json.getInt("n_estimators"),
                maxDepth = json.getInt("max_depth"),
                minSamplesSplit = json.optInt("min_samples_split", 2),
                minSamplesLeaf = json.optInt("min_samples_leaf", 1)
            )

            // Load class labels
            val labelsArr = json.getJSONArray("classes")
            clf.classes = Array(labelsArr.length()) { labelsArr.getString(it) }

            // Feature pipeline version (v3+ = session-invariant transform)
            clf.featureVersion = json.optInt("feature_version", 1)

            // Windowed-feature models (newer): the forest was trained on
            // per-feature window statistics, not single frames. Absent flag =
            // legacy per-frame model.
            clf.windowed = json.optBoolean("rfc_windowed", false)
            clf.window = json.optInt("rfc_window", RfcFeatureExtractor.WINDOW)

            // Session anchor (raw 21-ch REST mean) for runtime drift compensation.
            // Applied by SessionCalibrator to the raw frame BEFORE windowing.
            if (json.has("rest_baseline")) {
                val rb = json.getJSONArray("rest_baseline")
                clf.restBaseline = FloatArray(rb.length()) { rb.getDouble(it).toFloat() }
            }

            // Load trees
            val treesArr = json.getJSONArray("trees")
            clf.trees = Array(treesArr.length()) { i ->
                DecisionTree.fromJson(treesArr.getJSONObject(i))
            }
            clf.isTrained = true
            return clf
        }

        /**
         * Load from the models directory by model name.
         */
        fun loadFromModelsDir(context: Context, modelName: String): RandomForestClassifier? {
            val dir = context.getExternalFilesDir("models") ?: return null
            val file = File(dir, modelName)
            if (!file.exists()) return null
            return try {
                loadFromFile(file)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}", e)
                null
            }
        }
    }

    private var trees: Array<DecisionTree> = emptyArray()
    var classes: Array<String> = emptyArray()
        private set
    var isTrained: Boolean = false
        private set
    var featureVersion: Int = FeatureTransform.VERSION
        private set

    /** True when this forest expects windowed statistics from [RfcFeatureExtractor]. */
    var windowed: Boolean = false
    /** Window length (frames) used to build the statistics; only meaningful when [windowed]. */
    var window: Int = RfcFeatureExtractor.WINDOW

    /**
     * Raw 21-channel REST mean (`rest_baseline`), or null. SessionCalibrator uses
     * this to re-anchor each live power session to the reference the forest was
     * trained on, so predictions survive a glove power-cycle. Set by the training
     * site from the raw dataset (the forest itself trains on windowed features, so
     * it can't derive the raw-frame baseline on its own).
     */
    var restBaseline: FloatArray? = null

    private fun preprocess(features: FloatArray): FloatArray {
        // Windowed feature vectors are already final — don't run the per-frame
        // 21-channel transform over them.
        if (windowed) return features
        return if (featureVersion >= 3) FeatureTransform.apply(features) else features
    }

    /**
     * Train the Random Forest from feature matrix X and label array y.
     * Raw CSV rows are run through the session-invariant FeatureTransform
     * so the forest never splits on power-on-dependent values.
     */
    fun fit(rawX: Array<FloatArray>, y: Array<String>) {
        require(rawX.size == y.size) { "X and y must have the same number of samples" }
        require(rawX.isNotEmpty()) { "Training data cannot be empty" }

        featureVersion = FeatureTransform.VERSION
        val X = Array(rawX.size) { preprocess(rawX[it]) }

        classes = y.toSet().sorted().toTypedArray()
        val nFeatures = X[0].size
        // sqrt(n_features) per split — sklearn's classification default and what
        // this class's docstring + the web companion specify. The previous
        // 0.8*n_features made every tree consider almost the same features, so the
        // trees were highly correlated and the ensemble barely reduced variance.
        // sqrt decorrelates them, which is the single biggest RFC accuracy lever.
        val maxFeaturesPerSplit = kotlin.math.sqrt(nFeatures.toDouble())
            .toInt().coerceIn(1, nFeatures)
        val nSamples = X.size

        // Build the trees IN PARALLEL — each tree is independent and CPU-bound, so
        // this scales with the device's core count and is the main training
        // speedup. Each tree gets its own seeded RNG (bootstrap + split selection)
        // so the result stays deterministic regardless of completion order.
        trees = (0 until nEstimators).toList().parallelStream().map { treeIdx ->
            val rng = java.util.Random(randomSeed * 1_000_003L + treeIdx)
            val idxs = IntArray(nSamples) { rng.nextInt(nSamples) }
            val bootstrapX = Array(nSamples) { X[idxs[it]] }
            val bootstrapY = Array(nSamples) { y[idxs[it]] }
            val tree = DecisionTree(
                maxDepth = maxDepth,
                minSamplesSplit = minSamplesSplit,
                minSamplesLeaf = minSamplesLeaf,
                maxFeatures = maxFeaturesPerSplit,
                random = java.util.Random(randomSeed + treeIdx)
            )
            tree.fit(bootstrapX, bootstrapY, classes)
            tree
        }.collect(java.util.stream.Collectors.toList()).toTypedArray()

        isTrained = true
        Log.d(TAG, "Trained RFC with $nEstimators trees, ${classes.size} classes, $nFeatures features")
    }

    /**
     * Full vote distribution over [classes] (fraction of trees voting each class).
     * Used by the live prediction engine for the top1−top2 margin gate.
     */
    fun predictProbabilities(features: FloatArray): FloatArray {
        require(isTrained) { "Model must be trained before prediction" }
        val input = preprocess(features)
        val idxOf = HashMap<String, Int>(classes.size)
        for (i in classes.indices) idxOf[classes[i]] = i
        val counts = IntArray(classes.size)
        for (tree in trees) {
            val ci = idxOf[tree.predict(input)] ?: continue
            counts[ci]++
        }
        val out = FloatArray(classes.size)
        if (trees.isNotEmpty()) for (i in counts.indices) out[i] = counts[i].toFloat() / trees.size
        return out
    }

    /**
     * Predict the class label for a single 21-feature vector.
     * Matches webapp: clf.predict([normalized_vector])
     */
    fun predict(features: FloatArray): String {
        require(isTrained) { "Model must be trained before prediction" }
        val input = preprocess(features)
        val votes = mutableMapOf<String, Int>()
        for (tree in trees) {
            val prediction = tree.predict(input)
            votes[prediction] = (votes[prediction] ?: 0) + 1
        }
        return votes.maxByOrNull { it.value }?.key ?: classes[0]
    }

    /**
     * Predict with confidence (fraction of trees that voted for the winner).
     */
    fun predictWithConfidence(features: FloatArray): Pair<String, Float> {
        require(isTrained) { "Model must be trained before prediction" }
        val input = preprocess(features)
        val votes = mutableMapOf<String, Int>()
        for (tree in trees) {
            val prediction = tree.predict(input)
            votes[prediction] = (votes[prediction] ?: 0) + 1
        }
        val winner = votes.maxByOrNull { it.value }!!
        val confidence = winner.value.toFloat() / trees.size
        return Pair(winner.key, confidence)
    }

    /**
     * Calculate accuracy on a test set.
     */
    fun score(X: Array<FloatArray>, y: Array<String>): Float {
        var correct = 0
        for (i in X.indices) {
            if (predict(X[i]) == y[i]) correct++
        }
        return correct.toFloat() / X.size
    }

    /**
     * Save trained model to JSON file.
     */
    fun saveToFile(file: File) {
        require(isTrained) { "Model must be trained before saving" }
        val json = JSONObject()
        json.put("n_estimators", nEstimators)
        json.put("max_depth", maxDepth)
        json.put("min_samples_split", minSamplesSplit)
        json.put("min_samples_leaf", minSamplesLeaf)
        json.put("feature_version", featureVersion)
        json.put("rfc_windowed", windowed)
        json.put("rfc_window", window)
        restBaseline?.let { rb -> json.put("rest_baseline", JSONArray().apply { rb.forEach { put(it.toDouble()) } }) }

        val labelsArr = JSONArray()
        classes.forEach { labelsArr.put(it) }
        json.put("classes", labelsArr)

        val treesArr = JSONArray()
        trees.forEach { treesArr.put(it.toJson()) }
        json.put("trees", treesArr)

        file.parentFile?.mkdirs()
        file.writeText(json.toString())
        Log.d(TAG, "Model saved to ${file.absolutePath}")
    }

    fun saveToModelsDir(context: Context, modelName: String): File? {
        val dir = context.getExternalFilesDir("models") ?: return null
        dir.mkdirs()
        val file = File(dir, if (modelName.endsWith(".json")) modelName else "$modelName.json")
        return try {
            saveToFile(file)
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save model: ${e.message}", e)
            null
        }
    }
}

/**
 * Decision Tree node and training logic using Gini impurity.
 */
class DecisionTree(
    private val maxDepth: Int = 16,
    private val minSamplesSplit: Int = 2,
    private val minSamplesLeaf: Int = 1,
    private val maxFeatures: Int = 6,
    private val random: java.util.Random = java.util.Random(42)
) {
    companion object {
        fun fromJson(json: JSONObject): DecisionTree {
            val tree = DecisionTree()
            tree.root = TreeNode.fromJson(json.getJSONObject("root"))
            return tree
        }
    }

    private var root: TreeNode? = null

    fun fit(X: Array<FloatArray>, y: Array<String>, classes: Array<String>) {
        val classToIdx = classes.withIndex().associate { it.value to it.index }
        root = buildTree(X, y, IntArray(X.size) { it }, classes, classToIdx, 0)
    }

    fun predict(features: FloatArray): String {
        var node = root ?: return ""
        while (!node.isLeaf) {
            node = if (features[node.featureIndex] <= node.threshold) {
                node.left ?: return node.label
            } else {
                node.right ?: return node.label
            }
        }
        return node.label
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("root", root?.toJson() ?: JSONObject())
        return json
    }

    private fun quicksort(indices: IntArray, left: Int, right: Int, X: Array<FloatArray>, featureIdx: Int) {
        if (left >= right) return
        val pivot = X[indices[left + (right - left) / 2]][featureIdx]
        var i = left
        var j = right
        while (i <= j) {
            while (X[indices[i]][featureIdx] < pivot) i++
            while (X[indices[j]][featureIdx] > pivot) j--
            if (i <= j) {
                val temp = indices[i]
                indices[i] = indices[j]
                indices[j] = temp
                i++
                j--
            }
        }
        quicksort(indices, left, j, X, featureIdx)
        quicksort(indices, i, right, X, featureIdx)
    }

    private fun weightedGiniFromCounts(
        leftCounts: IntArray,
        leftSize: Int,
        rightCounts: IntArray,
        rightSize: Int
    ): Double {
        val total = (leftSize + rightSize).toDouble()
        val leftGini = giniImpurityFromCounts(leftCounts, leftSize)
        val rightGini = giniImpurityFromCounts(rightCounts, rightSize)
        return (leftSize / total) * leftGini + (rightSize / total) * rightGini
    }

    private fun giniImpurityFromCounts(counts: IntArray, size: Int): Double {
        if (size == 0) return 0.0
        val n = size.toDouble()
        var sumSq = 0.0
        for (c in counts) {
            val p = c / n
            sumSq += p * p
        }
        return 1.0 - sumSq
    }

    private fun buildTree(
        X: Array<FloatArray>,
        y: Array<String>,
        indices: IntArray,
        classes: Array<String>,
        classToIdx: Map<String, Int>,
        depth: Int
    ): TreeNode {
        // Count class distribution
        val classCounts = mutableMapOf<String, Int>()
        for (idx in indices) {
            val label = y[idx]
            classCounts[label] = (classCounts[label] ?: 0) + 1
        }
        val majorityClass = classCounts.maxByOrNull { it.value }?.key ?: classes[0]

        // Stopping conditions
        if (depth >= maxDepth || indices.size < minSamplesSplit || classCounts.size <= 1) {
            return TreeNode(label = majorityClass, isLeaf = true)
        }

        // Find best split using random feature subset
        val nFeatures = X[0].size
        val candidateFeatures = (0 until nFeatures).shuffled(random).take(maxFeatures)

        var bestGini = Double.MAX_VALUE
        var bestFeature = 0
        var bestThreshold = 0f
        var bestLeftIndices = intArrayOf()
        var bestRightIndices = intArrayOf()

        for (featureIdx in candidateFeatures) {
            // Sort a copy of the indices based on this feature's value
            val sortedIndices = indices.copyOf()
            quicksort(sortedIndices, 0, sortedIndices.size - 1, X, featureIdx)

            // Count target classes
            val rightCounts = IntArray(classes.size)
            val leftCounts = IntArray(classes.size)

            for (idx in indices) {
                val clsIdx = classToIdx[y[idx]] ?: 0
                rightCounts[clsIdx]++
            }

            var leftSize = 0
            var rightSize = indices.size

            for (i in 0 until sortedIndices.size - 1) {
                val sampleIdx = sortedIndices[i]
                val clsIdx = classToIdx[y[sampleIdx]] ?: 0

                leftCounts[clsIdx]++
                rightCounts[clsIdx]--
                leftSize++
                rightSize--

                val currentVal = X[sampleIdx][featureIdx]
                val nextVal = X[sortedIndices[i + 1]][featureIdx]

                if (currentVal < nextVal) {
                    if (leftSize >= minSamplesLeaf && rightSize >= minSamplesLeaf) {
                        val gini = weightedGiniFromCounts(leftCounts, leftSize, rightCounts, rightSize)
                        if (gini < bestGini) {
                            bestGini = gini
                            bestFeature = featureIdx
                            bestThreshold = (currentVal + nextVal) / 2f
                            bestLeftIndices = sortedIndices.sliceArray(0..i)
                            bestRightIndices = sortedIndices.sliceArray((i + 1) until sortedIndices.size)
                        }
                    }
                }
            }
        }

        // No valid split found — make a leaf
        if (bestLeftIndices.isEmpty() || bestRightIndices.isEmpty()) {
            return TreeNode(label = majorityClass, isLeaf = true)
        }

        return TreeNode(
            featureIndex = bestFeature,
            threshold = bestThreshold,
            label = majorityClass,
            isLeaf = false,
            left = buildTree(X, y, bestLeftIndices, classes, classToIdx, depth + 1),
            right = buildTree(X, y, bestRightIndices, classes, classToIdx, depth + 1)
        )
    }
}

/**
 * Node in a decision tree.
 */
data class TreeNode(
    val featureIndex: Int = 0,
    val threshold: Float = 0f,
    val label: String = "",
    val isLeaf: Boolean = true,
    val left: TreeNode? = null,
    val right: TreeNode? = null
) {
    companion object {
        fun fromJson(json: JSONObject): TreeNode {
            val isLeaf = json.getBoolean("isLeaf")
            return TreeNode(
                featureIndex = json.optInt("featureIndex", 0),
                threshold = json.optDouble("threshold", 0.0).toFloat(),
                label = json.getString("label"),
                isLeaf = isLeaf,
                left = if (!isLeaf && json.has("left")) fromJson(json.getJSONObject("left")) else null,
                right = if (!isLeaf && json.has("right")) fromJson(json.getJSONObject("right")) else null
            )
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("isLeaf", isLeaf)
        json.put("label", label)
        if (!isLeaf) {
            json.put("featureIndex", featureIndex)
            json.put("threshold", threshold.toDouble())
            left?.let { json.put("left", it.toJson()) }
            right?.let { json.put("right", it.toJson()) }
        }
        return json
    }
}
