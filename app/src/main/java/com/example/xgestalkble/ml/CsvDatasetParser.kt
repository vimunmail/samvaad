package com.example.xgestalkble.ml

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Parses v3.1 CSV datasets written by DataCollector.
 * Layout: Label (col 0), timestamp (col 1), packetCounter (col 2), 21 feature columns (cols 3-23).
 */
object CsvDatasetParser {
    private const val TAG = "CsvParser"
    // 21 features matching DataNormalizer.NUM_FEATURES (v3.1 firmware)
    private const val NUM_FEATURES = 21

    data class Dataset(
        val X: Array<FloatArray>,
        val y: Array<String>,
        val labelCounts: Map<String, Int>,
        /** Empty for legacy rows without a session column. Never a model feature. */
        val sessionIds: Array<String> = Array(y.size) { "" }
    )

    /**
     * Parse a CSV file into feature matrix X and label array y.
     * Layout: Label (col 0), timestamp, packetCounter, then 21 feature columns
     * (cols 3-23). A trailing `session` column (col 24, written by newer
     * DataCollector recordings) is ignored here — features are read by position,
     * so both old (24-col) and new (25-col) files parse identically.
     */
    fun parse(file: File): Dataset? {
        if (!file.exists() || !file.isFile) {
            Log.e(TAG, "File not found: ${file.absolutePath}")
            return null
        }

        val features = mutableListOf<FloatArray>()
        val labels = mutableListOf<String>()
        val sessionIds = mutableListOf<String>()
        val labelCounts = mutableMapOf<String, Int>()

        try {
            file.bufferedReader().use { reader ->
                // Skip header
                val header = reader.readLine() ?: return null
                Log.d(TAG, "CSV header: $header")
                val headerCols = header.split(",")
                val sessionColumn = headerCols.indexOfFirst { it.trim().equals("session", ignoreCase = true) }

                var lineNum = 1
                var line = reader.readLine()
                while (line != null) {
                    lineNum++
                    val cols = line.split(",")
                    
                    if (cols.size >= NUM_FEATURES + 3) {
                        val label = cols[0].trim()
                        if (label.isNotEmpty()) {
                            try {
                                val featureRow = FloatArray(NUM_FEATURES)
                                for (i in 0 until NUM_FEATURES) {
                                    featureRow[i] = cols[i + 3].trim().toFloat()
                                }
                                features.add(featureRow)
                                labels.add(label)
                                sessionIds.add(
                                    if (sessionColumn >= 0 && sessionColumn < cols.size) {
                                        cols[sessionColumn].trim()
                                    } else {
                                        ""
                                    }
                                )
                                labelCounts[label] = (labelCounts[label] ?: 0) + 1
                            } catch (e: NumberFormatException) {
                                Log.w(TAG, "Skipping line $lineNum: parse error")
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CSV: ${e.message}", e)
            return null
        }

        if (features.isEmpty()) {
            Log.w(TAG, "No valid data rows found in CSV")
            return null
        }

        Log.d(TAG, "Parsed ${features.size} samples, ${labelCounts.size} classes: $labelCounts")
        return Dataset(
            X = features.toTypedArray(),
            y = labels.toTypedArray(),
            labelCounts = labelCounts,
            sessionIds = sessionIds.toTypedArray()
        )
    }

    /**
     * Parse from the TelemetryData directory by filename.
     */
    fun parseFromTelemetryDir(context: Context, csvFileName: String): Dataset? {
        val dir = context.getExternalFilesDir("TelemetryData") ?: return null
        val file = File(dir, csvFileName)
        return parse(file)
    }

    /**
     * Split dataset into train and test sets with stratification.
     * Matches webapp: test_size=0.2, random_state=42
     */
    fun trainTestSplit(
        dataset: Dataset,
        testRatio: Float = 0.2f,
        seed: Long = 42L
    ): Pair<Dataset, Dataset> {
        val random = java.util.Random(seed)
        val classes = dataset.y.toSet().sorted()
        
        val trainX = mutableListOf<FloatArray>()
        val trainY = mutableListOf<String>()
        val trainSessions = mutableListOf<String>()
        val testX = mutableListOf<FloatArray>()
        val testY = mutableListOf<String>()
        val testSessions = mutableListOf<String>()

        // Stratify: split each class proportionally
        for (cls in classes) {
            val classIndices = dataset.y.indices.filter { dataset.y[it] == cls }.toMutableList()
            classIndices.shuffle(random)
            
            val testCount = (classIndices.size * testRatio).toInt().coerceAtLeast(1)
            val testIndices = classIndices.take(testCount).toSet()
            
            for (idx in classIndices) {
                if (idx in testIndices) {
                    testX.add(dataset.X[idx])
                    testY.add(dataset.y[idx])
                    testSessions.add(dataset.sessionIds[idx])
                } else {
                    trainX.add(dataset.X[idx])
                    trainY.add(dataset.y[idx])
                    trainSessions.add(dataset.sessionIds[idx])
                }
            }
        }

        val trainLabelCounts = mutableMapOf<String, Int>()
        trainY.forEach { trainLabelCounts[it] = (trainLabelCounts[it] ?: 0) + 1 }
        val testLabelCounts = mutableMapOf<String, Int>()
        testY.forEach { testLabelCounts[it] = (testLabelCounts[it] ?: 0) + 1 }

        return Pair(
            Dataset(trainX.toTypedArray(), trainY.toTypedArray(), trainLabelCounts, trainSessions.toTypedArray()),
            Dataset(testX.toTypedArray(), testY.toTypedArray(), testLabelCounts, testSessions.toTypedArray())
        )
    }
}
