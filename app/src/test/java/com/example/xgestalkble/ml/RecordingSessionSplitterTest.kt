package com.example.xgestalkble.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionSplitterTest {

    @Test
    fun rfcUsesWholeSessionsForValidation() {
        val features = Array(60) { index ->
            val value = when (index) {
                in 0 until 15 -> 1f
                in 15 until 30 -> 2f
                in 30 until 45 -> 3f
                else -> 4f
            }
            FloatArray(21) { value }
        }
        val labels = Array(60) { index -> if (index < 30) "A" else "B" }
        val sessions = Array(60) { index ->
            when (index) {
                in 0 until 15 -> "a-1"
                in 15 until 30 -> "a-2"
                in 30 until 45 -> "b-1"
                else -> "b-2"
            }
        }

        val (train, validation) = RfcFeatureExtractor.buildWindowedDatasetSplit(
            features, labels, window = 15, testRatio = 0.2f, sessionIds = sessions
        )

        assertEquals(listOf("A", "B"), train.second.toList())
        assertEquals(listOf("A", "B"), validation.second.toList())
        assertEquals(setOf(1f, 3f), train.first.map { it[0] }.toSet())
        assertEquals(setOf(2f, 4f), validation.first.map { it[0] }.toSet())
    }

    @Test
    fun tcnNeverBuildsASequenceAcrossSessionBoundary() {
        val features = Array(60) { index -> FloatArray(21) { if (index < 30) 1f else 2f } }
        val labels = Array(60) { "A" }
        val sessions = Array(60) { index -> if (index < 30) "take-1" else "take-2" }

        val (sequences, sequenceLabels) = TcnTrainer.createTcnSequences(
            features, labels, seqLen = 30, sessionIds = sessions
        )

        assertEquals(listOf("A", "A"), sequenceLabels)
        assertEquals(2, sequences.size)
        assertTrue(sequences.all { sequence -> sequence.all { it[0] == sequence[0][0] } })
    }
}
