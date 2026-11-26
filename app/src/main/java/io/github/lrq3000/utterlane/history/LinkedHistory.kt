package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.transcribe.QueueJournal
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.asr.WorkingTranscriptCopy
import java.io.File

enum class HistoryDeletionTarget { AUDIO, TRANSCRIPTS, BOTH }

/** Exact identities shown in a confirmation, including an unsaved working result's
 * stable attempt ID. New sibling versions must never silently join this set. */
data class HistoryDeletionPlan(
    val audioId: String?,
    val transcriptIds: Set<String>,
    val allLinked: Boolean
) {
    val choices: List<HistoryDeletionTarget> get() = when {
        audioId != null && transcriptIds.isNotEmpty() -> HistoryDeletionTarget.entries.toList()
        audioId != null -> listOf(HistoryDeletionTarget.AUDIO)
        transcriptIds.isNotEmpty() -> listOf(HistoryDeletionTarget.TRANSCRIPTS)
        else -> emptyList()
    }
}

/** Relationship queries and explicit deletion, independent of Android/UI owners.
 * Call on IO: metadata presence alone does not prove that the payload still exists. */
class LinkedHistory internal constructor(private val recordings: RecordingHistory, private val transcripts: TranscriptHistory,
    private val queue: QueueJournal?, private val targetFile: ((String) -> File)?) {
    constructor(recordings: RecordingHistory, transcripts: TranscriptHistory) : this(recordings, transcripts, null, null)

    /** Exact queue attempt and its working file's saved identity, not every version linked to the same recording. A stale view can hold either identity after another owner publishes Keep. */
    private fun protectedTextIds(ids: Set<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val protected = ids.filterTo(mutableSetOf(), transcripts.awaitingQueueReview)
        val journal = queue ?: return protected
        val candidateSources = (ids - protected).mapTo(HashSet()) { transcripts.find(it)?.audioId }
        for (row in journal.records()) {
            if (row.textId in ids) protected.add(row.textId)
            if (protected.size == ids.size) break
            if (null !in candidateSources && row.audio !in candidateSources) continue
            row.provenanceAlias(checkNotNull(targetFile))?.takeIf { it in ids }?.let(protected::add)
        }
        return protected
    }

    /** The path itself is authoritative for an append target, including before its provenance is published. A similarly named ordinary cache copy is not the queue's file. */
    internal fun isQueueTarget(file: File): Boolean {
        val row = queue?.find(file.nameWithoutExtension) ?: return false
        return file.canonicalFile == checkNotNull(targetFile)(row.id).canonicalFile
    }

    /** Called inside confirmDeletion's publication transaction; return only the dialog-owned files actually marked. */
    internal fun discardWorking(ids: Set<String>, owned: List<File>, copies: List<WorkingTranscriptCopy>): List<File> {
        val selected = owned.filter { file -> file.isFile && !isQueueTarget(file) && WorkingTranscriptCopy(file, TranscriptSource.read(file)).id in ids }
        val linked = copies.filter { it.id in ids && !isQueueTarget(it.file) }.map { it.file }
        (selected + linked).toSet().forEach(TranscriptStore::deleteArtifacts)
        return selected
    }

    fun plan(audioId: String?, transcriptId: String?, transcriptOrigin: Boolean,
        workingTranscriptId: String? = null, linkedWorkingIds: Set<String> = emptySet()): HistoryDeletionPlan {
        val current = transcripts.find(transcriptId)
        val sourceId = current?.audioId ?: audioId
        val text = if (transcriptOrigin) listOfNotNull(current) else sourceId?.let(transcripts::forAudio).orEmpty()
        val ids = text.filter { it.file.isFile && it.file.length() > 0 }.mapTo(linkedSetOf()) { it.id }
        // A manual save uses this same attempt ID, so a save that finishes while
        // the question is open neither doubles the count nor evades deletion.
        workingTranscriptId?.let(ids::add)
        if (!transcriptOrigin) ids.addAll(linkedWorkingIds)
        // A reader lease makes a transcript-only import playable, but does not turn the temporary processing copy into an audio-history deletion choice.
        val deletableAudio = synchronized(recordings) {
            sourceId?.takeUnless(recordings.awaitingQueueReview)?.let(::availableAudio)?.takeUnless { it.transcriptOnlyStaging }?.id
        }
        return HistoryDeletionPlan(deletableAudio, ids - protectedTextIds(ids), allLinked = !transcriptOrigin)
    }

    fun availableAudio(id: String?): HistoryEntry? {
        if (id == null) return null
        recordings.initialize()
        val entry = try { recordings.get(id) } catch (_: IllegalStateException) { return null }
        if (entry.status in setOf("active", "importing", "discarded") || entry.parts <= 0) return null
        return entry.takeIf { recordings.available(it) }
    }

    private fun <T> withDeletionLocks(action: () -> T) = transcripts.withPublicationLock {
        // Registration and exact alias publication cannot slip between the scope check and marking the working files. Match the dedicated cancel path's lock order.
        synchronized(recordings) { if (queue != null) synchronized(queue) { action() } else action() }
    }

    fun delete(plan: HistoryDeletionPlan, target: HistoryDeletionTarget) = withDeletionLocks {
        require(target in plan.choices) { "The selected items are unavailable" }
        // A saved-history dialog has no authority to stop a queue producer or invalidate a paused/retry input. Registration uses this same recording monitor; a stale Both must fail BEFORE deleting any text.
        if (target != HistoryDeletionTarget.TRANSCRIPTS)
            check(!recordings.awaitingQueueReview(checkNotNull(plan.audioId))) { "The transcription queue still needs this recording" }
        if (target != HistoryDeletionTarget.AUDIO)
            check(protectedTextIds(plan.transcriptIds).isEmpty()) { "Open the queued transcription to delete this result" }
        // Do not re-query all related results here: confirmation authorized only
        // these IDs. Existing lease-aware tombstones handle delayed file removal.
        if (target != HistoryDeletionTarget.AUDIO) plan.transcriptIds.forEach(transcripts::delete)
        if (target != HistoryDeletionTarget.TRANSCRIPTS) plan.audioId?.let(recordings::delete)
        Unit
    }

    /** Returns a new question when identities expanded, or null after deleting
     * exactly the confirmed scope. The caller supplies its working-copy view. */
    fun confirmDeletion(plan: HistoryDeletionPlan, target: HistoryDeletionTarget,
        refresh: () -> HistoryDeletionPlan, discardWorking: (Set<String>) -> Unit): HistoryDeletionPlan? = withDeletionLocks {
        require(target in plan.choices) { "The selected items are unavailable" }
        // A different dialog can publish Keep; joining only this dialog's job is
        // insufficient. Recheck, mark and delete under the same lock as complete
        // Keep publication so a new identity must receive renewed confirmation.
        val refreshed = refresh()
        val protected = if (target == HistoryDeletionTarget.AUDIO) emptySet() else protectedTextIds(plan.transcriptIds + refreshed.transcriptIds)
        val fresh = refreshed.copy(audioId = refreshed.audioId?.takeUnless(recordings.awaitingQueueReview),
            transcriptIds = refreshed.transcriptIds - protected)
        val expanded = target != HistoryDeletionTarget.AUDIO && !plan.transcriptIds.containsAll(fresh.transcriptIds)
        val changedAudio = target != HistoryDeletionTarget.TRANSCRIPTS && fresh.audioId != plan.audioId
        if (expanded || changedAudio || protected.any { it in plan.transcriptIds }) {
            check(fresh.choices.isNotEmpty()) { "The selected items are no longer available to delete" }
            return@withDeletionLocks fresh
        }
        if (target != HistoryDeletionTarget.AUDIO) discardWorking(plan.transcriptIds)
        delete(plan, target)
        null
    }
}
