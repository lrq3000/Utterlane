package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.asr.WorkingTranscriptCopy
import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.history.TranscriptHistory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serialize the last-owner decision through acknowledgment. Otherwise two views can each see another queue owner, both skip retention cleanup, then both unlink their descriptors. */
internal class QueueDisposition {
    private val mutex = Mutex()
    suspend fun <T> run(action: suspend () -> T): T = mutex.withLock { action() }
}

internal enum class QueueDeleteAudio { NONE, STAGING, OPTIONAL }

internal data class QueueDeleteQuestion(
    val id: String, val sequence: Long, val label: String, val source: String,
    val textIds: Set<String>, val audio: QueueDeleteAudio
) {
    val canDeleteAudio get() = audio == QueueDeleteAudio.OPTIONAL
}

/** The queue ID owns an append target even before speech is recognized. The recording ID identifies an input, never the set of results to erase. */
internal class QueueAttemptDeletion(
    private val recordings: RecordingHistory, private val texts: TranscriptHistory, private val journal: QueueJournal,
    private val target: (String) -> File, private val viewDirectory: File
) {
    /** Read-only until the positive button. Do not audition audio or read transcript bodies to build the question. */
    fun question(id: String): QueueDeleteQuestion? = texts.withPublicationLock {
        val row = journal.find(id)?.takeUnless { it.stage == QueueStage.CAPTURING || it.stage == QueueStage.DRAINING }
            ?: return@withPublicationLock null
        val views = TranscriptSource.copies(viewDirectory)
        synchronized(recordings) { describe(row, views) }
    }

    private fun describe(row: QueueRecord, views: List<WorkingTranscriptCopy>): QueueDeleteQuestion {
        val source = TranscriptSource.read(target(row.id))
        check(source.audioId == null || source.audioId == row.audio) { "The transcript source changed" }
        val ids = setOfNotNull(row.textId, source.transcriptId)
        for (id in ids) {
            check(id == row.textId || !journal.protectsText(id)) { "The transcript belongs to another job" }
            val saved = texts.find(id)
            check(saved?.audioId == null || saved.audioId == row.audio) { "The saved transcript source changed" }
        }
        recordings.initialize()
        val entry = try { recordings.get(row.audio) } catch (_: IllegalStateException) { null }
        val audio = when {
            entry == null || entry.status in setOf("discarded", "active", "importing") -> QueueDeleteAudio.NONE
            entry.transcriptOnlyStaging -> QueueDeleteAudio.STAGING
            journal.anotherOwnsAudio(row.audio, row.id) -> QueueDeleteAudio.NONE
            // A saved or recoverable sibling still has replay/retry rights to retained audio after its queue row is acknowledged.
            texts.forAudio(row.audio).any { it.id !in ids } || views.any { it.source.audioId == row.audio && it.id !in ids } -> QueueDeleteAudio.NONE
            entry.parts > 0 && recordings.available(entry) -> QueueDeleteAudio.OPTIONAL
            else -> QueueDeleteAudio.NONE
        }
        return QueueDeleteQuestion(row.id, row.sequence, row.label, row.audio, ids, audio)
    }

    /** Return a renewed question without deleting anything if Keep, a sibling retry or a pin changed what confirmation would remove. A null result means the exact row was retired or already acknowledged. */
    suspend fun confirm(plan: QueueDeleteQuestion, deleteAudio: Boolean, stop: suspend (String) -> Boolean,
        acknowledge: suspend (String) -> Unit): QueueDeleteQuestion? {
        require(!deleteAudio || plan.canDeleteAudio)
        if (!stop(plan.id)) {
            if (withContext(Dispatchers.IO) { journal.find(plan.id) } == null) return null
            error("Could not stop this transcription")
        }
        val renewed = withContext(Dispatchers.IO) {
            texts.withPublicationLock {
                val views = TranscriptSource.copies(viewDirectory)
                // Publication is history -> source; source enrollment is recordings -> journal. Never join a producer, notify the UI, or suspend under these locks.
                synchronized(recordings) { synchronized(journal) {
                    val row = journal.find(plan.id) ?: return@withPublicationLock null
                    check(row.stage.reviewable) { "The transcription is still active" }
                    val fresh = describe(row, views)
                    if (fresh != plan) return@withPublicationLock fresh
                    // Delete history before the sidecar containing an explicit Keep's non-default ID. A disk failure can then be retried using the same durable provenance.
                    fresh.textIds.forEach(texts::delete)
                    views.filter { it.id in fresh.textIds && (it.source.audioId == null || it.source.audioId == row.audio) }
                        .forEach { TranscriptStore.deleteArtifacts(it.file) }
                    TranscriptStore.deleteArtifacts(target(row.id)) // Also retires a sidecar without text.
                    if (fresh.audio == QueueDeleteAudio.STAGING && !journal.anotherOwnsAudio(row.audio, row.id)) recordings.retireCancelledImport(row.audio)
                    else if (deleteAudio) recordings.delete(row.audio)
                    null
                } }
            }
        }
        if (renewed == null) acknowledge(plan.id) // Existing delivery path withdraws only this job's notification and releases queue retention.
        return renewed
    }
}
