package io.github.lrq3000.utterlane.asr

/**
 * Worker-side translation from per-invocation, per-stage cumulative native counters
 * to one strictly increasing request sequence and completed-work count. Never infer
 * progress from CPU use, JNI entry, or a heartbeat. Native callbacks must represent
 * completed computational units, without words/audio in their stage names.
 */
internal class RecognitionProgressReporter(
    private val requestId: Int,
    private val send: (RecognitionProgress) -> Unit
) : AutoCloseable {
    private var sequence = 0L
    private var units = 0L
    private var closed = false
    private val counts = mutableMapOf<String, Long>()
    private val completedStages = mutableSetOf<String>()
    private val generations = mutableMapOf<String, Long>()

    @Synchronized fun stage(name: String) { emit(name, completed = false, opaque = true) }

    @Synchronized fun completeStage(name: String) {
        if (!closed && completedStages.add(name)) advance(1, name, opaque = true)
    }

    @Synchronized fun nativeProgress(completed: Long, stage: String) {
        nativeProgress(completed, stage, counts)
    }

    /** A new invocation invalidates any late callback from the previous invocation. */
    @Synchronized fun callback(scope: String): (Long, String) -> Unit {
        val generation = (generations[scope] ?: 0) + 1
        generations[scope] = generation
        val counters = mutableMapOf<String, Long>()
        return { count, stage ->
            synchronized(this) {
                if (generations[scope] == generation) nativeProgress(count, "$scope/$stage", counters)
            }
        }
    }

    @Synchronized fun endCallback(scope: String) {
        generations[scope] = (generations[scope] ?: 0) + 1
    }

    private fun nativeProgress(completed: Long, stage: String, counters: MutableMap<String, Long>) {
        if (closed || completed < 0 || stage.isBlank()) return
        val previous = counters[stage]
        if (previous == null && completed == 0L) {
            counters[stage] = 0
            stage(stage) // Announce entry without pretending any work has finished.
        } else if (completed > (previous ?: 0)) {
            counters[stage] = completed
            advance(completed - (previous ?: 0), stage, opaque = false)
        }
    }

    private fun advance(delta: Long, stage: String, opaque: Boolean) {
        // Native counters are Longs; do not let a corrupt/wrapped count renew forever.
        if (delta > Long.MAX_VALUE - units) return
        units += delta
        emit(stage, completed = true, opaque = opaque)
    }

    private fun emit(stage: String, completed: Boolean, opaque: Boolean) {
        if (!closed) send(RecognitionProgress(requestId, ++sequence, stage, units, completed, opaque))
    }

    @Synchronized override fun close() { closed = true }
}
