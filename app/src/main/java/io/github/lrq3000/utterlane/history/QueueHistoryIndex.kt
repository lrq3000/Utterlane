package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.transcribe.QueueRecord
import io.github.lrq3000.utterlane.transcribe.QueueStage
import java.io.File

/** Strict identity lookup for the exact attempt. A pruned saved copy does not sever the working target's association with its former saved ID. */
internal fun QueueRecord.provenanceAlias(target: (String) -> File): String? {
    val source = TranscriptSource.read(target(id))
    return source.transcriptId?.takeIf { !source.discarded && source.audioId == audio && it != textId }
}

/** The job describes a transcription attempt, not a recording. Retries can share both the same audio ID and the same display name. */
internal class QueueHistoryIndex(private val jobs: List<QueueRecord>, private val savedAliases: Map<String, QueueRecord> = emptyMap(),
    private val historyRevision: Long = -1) {
    companion object {
        /** IO only. Read the exact attempt's small provenance sidecar rather than choosing one of a recording's potentially many saved versions. */
        fun fromProvenance(jobs: List<QueueRecord>, texts: TranscriptHistory, target: (String) -> File,
            revision: Long = texts.revision.value): QueueHistoryIndex {
            val canonical = jobs.mapTo(HashSet()) { it.textId }
            val hasCandidates = HashMap<String, Boolean>()
            val aliases = buildMap {
                for (row in jobs) {
                    if (!row.stage.reviewable) continue
                    // Keep publishes its history revision before attaching the saved identity. The publication lock makes its complete sidecar visible even on that first revision.
                    val id = texts.withPublicationLock {
                        try {
                            // Most recordings have only filename-derived saved IDs (or no saved history). Consult the repository's in-memory audio index before touching any sidecar.
                            val candidates = hasCandidates.getOrPut(row.audio) {
                                texts.forAudio(row.audio).any { it.id !in canonical }
                            }
                            if (!candidates) return@withPublicationLock null
                            row.provenanceAlias(target)?.takeIf { it !in canonical && texts.find(it)?.audioId == row.audio }
                        } catch (error: Exception) {
                            // Keep the exact queue route and the independent saved row usable if their optional association cannot be proved.
                            java.util.logging.Logger.getLogger("QueueHistoryIndex").log(java.util.logging.Level.WARNING, "Could not read saved transcript association", error)
                            null
                        }
                    }
                    if (id != null) put(id, row)
                }
            }
            return QueueHistoryIndex(jobs, aliases, revision)
        }
    }
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

    fun jobForText(id: String) = byText[id] ?: savedAliases[id]
    fun isCurrent(rows: List<QueueRecord>, revision: Long) = jobs === rows && historyRevision == revision

    /** Loaded paged entries retain their normal preview and chronology. An attempt with no loaded copy (including immediate retention or no output yet) still has an independent route to review. */
    fun withoutHistoryRows(loadedTextIds: Set<String>): List<QueueRecord> {
        val represented = loadedTextIds.mapNotNullTo(HashSet()) { jobForText(it)?.id }
        return queueRows.filterNot { it.id in represented }
    }
}

/** Capture and its post-Stop drain are owned by the live pipeline. Neither can be retired by the file cancellation command. */
internal val QueueStage.canCancelFromList: Boolean
    get() = this == QueueStage.WAITING || this == QueueStage.PROCESSING || this == QueueStage.PAUSED || reviewable
