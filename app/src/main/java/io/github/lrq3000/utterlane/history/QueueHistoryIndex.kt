package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.transcribe.QueueRecord
import io.github.lrq3000.utterlane.transcribe.QueueStage

/** The job describes a transcription attempt, not a recording. Retries can share both the same audio ID and the same display name. */
internal class QueueHistoryIndex(jobs: List<QueueRecord>) {
    // Home needs only the count and headline. Avoid deriving UUIDs for every unreviewed attempt there.
    val queueRows by lazy(LazyThreadSafetyMode.NONE) {
        val (unfinished, reviews) = jobs.partition { !it.stage.reviewable }
        unfinished + reviews
    }
    private val byText by lazy(LazyThreadSafetyMode.NONE) { queueRows.associateBy { it.textId } }
    private val active = jobs.filter { it.stage.needsProcessingService || it.stage == QueueStage.CAPTURING }
    val monitorCount = active.size
    val monitorJob = active.firstOrNull { it.stage != QueueStage.WAITING } ?: active.firstOrNull()
    val monitorRows by lazy(LazyThreadSafetyMode.NONE) { jobs.filterNot { it.stage.reviewable } }

    fun jobForText(id: String) = byText[id]

    /** Loaded paged entries retain their normal preview and chronology. An attempt with no loaded copy (including immediate retention or no output yet) still has an independent route to review. */
    fun withoutHistoryRows(loadedTextIds: Set<String>): List<QueueRecord> =
        buildList { for ((id, row) in byText) if (id !in loadedTextIds) add(row) }
}

/** Capture and its post-Stop drain are owned by the live pipeline. Neither can be retired by the file cancellation command. */
internal val QueueStage.canCancelFromList: Boolean
    get() = this == QueueStage.WAITING || this == QueueStage.PROCESSING || this == QueueStage.PAUSED || reviewable
