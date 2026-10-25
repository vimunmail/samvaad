package com.example.xgestalkble.ml

/**
 * Keeps separate recording takes isolated while creating model windows.
 *
 * DataCollector assigns every START a unique session id. Windows must not cross
 * that boundary, even when two adjacent takes have the same label. For labels
 * with two or more sessions, the most recent session is kept entirely for
 * validation. Legacy CSVs have no session column and retain the previous
 * label-boundary behaviour.
 */
internal object RecordingSessionSplitter {
    data class Block(
        val start: Int,
        val endExclusive: Int,
        val label: String,
        val sessionId: String
    )

    fun blocks(labels: Array<String>, sessionIds: Array<String>?): List<Block> {
        if (labels.isEmpty()) return emptyList()
        val sessionAware = sessionIds != null &&
            sessionIds.size == labels.size &&
            sessionIds.any { it.isNotBlank() }

        fun sessionAt(index: Int): String = if (sessionAware) sessionIds!![index] else ""

        val out = mutableListOf<Block>()
        var start = 0
        var label = labels[0]
        var session = sessionAt(0)
        for (i in 1 until labels.size) {
            val nextSession = sessionAt(i)
            if (labels[i] != label || nextSession != session) {
                out += Block(start, i, label, session)
                start = i
                label = labels[i]
                session = nextSession
            }
        }
        out += Block(start, labels.size, label, session)
        return out
    }

    /**
     * Whole sessions reserved for validation. A one-session label cannot have a
     * session holdout, so callers use their existing temporal split for it.
     */
    fun validationSessions(blocks: List<Block>, testRatio: Float): Set<Pair<String, String>> {
        val validation = mutableSetOf<Pair<String, String>>()
        for ((label, labelBlocks) in blocks.groupBy { it.label }) {
            val sessions = labelBlocks.map { it.sessionId }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            if (sessions.size < 2) continue
            val count = (sessions.size * testRatio).toInt()
                .coerceAtLeast(1)
                .coerceAtMost(sessions.size - 1)
            sessions.takeLast(count).forEach { validation += label to it }
        }
        return validation
    }
}
