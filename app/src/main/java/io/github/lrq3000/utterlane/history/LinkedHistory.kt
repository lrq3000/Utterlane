package io.github.lrq3000.utterlane.history

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
class LinkedHistory(private val recordings: RecordingHistory, private val transcripts: TranscriptHistory) {
    fun plan(audioId: String?, transcriptId: String?, transcriptOrigin: Boolean,
        workingTranscriptId: String? = null): HistoryDeletionPlan {
        val current = transcripts.find(transcriptId)
        val sourceId = current?.audioId ?: audioId
        val text = if (transcriptOrigin) listOfNotNull(current) else sourceId?.let(transcripts::forAudio).orEmpty()
        val ids = text.filter { it.file.isFile && it.file.length() > 0 }.mapTo(linkedSetOf()) { it.id }
        // A manual save uses this same attempt ID, so a save that finishes while
        // the question is open neither doubles the count nor evades deletion.
        workingTranscriptId?.let(ids::add)
        return HistoryDeletionPlan(availableAudio(sourceId)?.id, ids.toSet(), allLinked = !transcriptOrigin)
    }

    fun availableAudio(id: String?): HistoryEntry? {
        if (id == null) return null
        recordings.initialize()
        val entry = try { recordings.get(id) } catch (_: IllegalStateException) { return null }
        if (entry.status in setOf("active", "importing", "discarded") || entry.parts <= 0) return null
        return entry.takeIf { (0 until it.parts).all { part -> it.part(part).isFile } }
    }

    fun delete(plan: HistoryDeletionPlan, target: HistoryDeletionTarget) {
        require(target in plan.choices) { "The selected items are unavailable" }
        // Do not re-query all related results here: confirmation authorized only
        // these IDs. Existing lease-aware tombstones handle delayed file removal.
        if (target != HistoryDeletionTarget.AUDIO) plan.transcriptIds.forEach(transcripts::delete)
        if (target != HistoryDeletionTarget.TRANSCRIPTS) plan.audioId?.let(recordings::delete)
    }

    /** Returns a new question when identities expanded, or null after deleting
     * exactly the confirmed scope. The caller supplies its working-copy view. */
    fun confirmDeletion(plan: HistoryDeletionPlan, target: HistoryDeletionTarget,
        refresh: () -> HistoryDeletionPlan, discardWorking: (Set<String>) -> Unit): HistoryDeletionPlan? = transcripts.withPublicationLock {
        // A different dialog can publish Keep; joining only this dialog's job is
        // insufficient. Recheck, mark and delete under the same lock as complete
        // Keep publication so a new identity must receive renewed confirmation.
        val fresh = refresh()
        val expanded = target != HistoryDeletionTarget.AUDIO && !plan.transcriptIds.containsAll(fresh.transcriptIds)
        val changedAudio = target != HistoryDeletionTarget.TRANSCRIPTS && fresh.audioId != null && fresh.audioId != plan.audioId
        if (expanded || changedAudio) return@withPublicationLock fresh
        if (target != HistoryDeletionTarget.AUDIO) discardWorking(plan.transcriptIds)
        delete(plan, target)
        null
    }
}
