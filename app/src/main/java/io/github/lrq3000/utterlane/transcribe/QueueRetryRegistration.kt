package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.history.RecordingHistory

internal class QueueRetryRegistration(private val recordings: RecordingHistory, private val journal: QueueJournal) {
    /** A lease delays unlink but cannot prevent a deletion tombstone. Confirmed deletion uses transcript history → recording history → journal; hold the latter two in that order across validation and sibling publication. */
    fun add(audio: String, speakers: Boolean, modelId: String?, fallbackLabel: String, previous: String? = null): QueueRecord =
        synchronized(recordings) {
            recordings.acquire(audio, true).use {
                val entry = recordings.get(audio)
                check(entry.status !in setOf("discarded", "active", "importing") && entry.parts > 0 && recordings.available(entry)) { "Recording is unavailable" }
                val sibling = synchronized(journal) {
                    val parent = previous?.let(journal::find)
                    check(previous == null || parent?.audio == audio) { "Transcription is no longer available" }
                    journal.add(audio, parent?.label ?: entry.importMetadata?.displayName ?: fallbackLabel, speakers, modelId = modelId)
                }
                recordings.refreshQueueHold(audio)
                sibling
            }
        }
}
