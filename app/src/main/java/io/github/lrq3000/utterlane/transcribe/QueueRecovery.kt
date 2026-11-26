package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.asr.TranscriptCompletion
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.history.RecordingHistory
import java.io.File

/** Runs on IO before dispatching work. Never start a second append producer on a partially written attempt or treat a native EOF as successful capture. */
internal class QueueRecovery(private val journal: QueueJournal, private val recordings: RecordingHistory, private val target: (String) -> File) {
    fun restore(checkpoint: DialogInput?, label: String, interrupted: String): List<QueueRecord> {
        val id = checkpoint?.queueId
        if (id != null && checkpoint.audioId != null && journal.find(id) == null) {
            // Home and the queue are independently atomic. The first descriptor can reach disk just before process death prevents the second write.
            recordings.initialize()
            val audio = runCatching { recordings.get(checkpoint.audioId) }.getOrNull()
            val text = target(id)
            if (audio?.let { it.samples > 0 && it.status != "active" && it.status != "discarded" } == true ||
                (text.length() > 0 && !TranscriptSource.read(text).discarded)) {
                journal.add(checkpoint.audioId, label, audio?.captureSpeakerRequest ?: (checkpoint.metadata?.speakerLabels == true || audio?.speakerLabels == true), QueueStage.DRAINING, id)
                recordings.refreshQueueHold(checkpoint.audioId)
            }
        }
        return journal.records().filter { it.stage in setOf(QueueStage.CAPTURING, QueueStage.DRAINING, QueueStage.PROCESSING, QueueStage.PAUSING, QueueStage.STOPPING) }.map { row ->
            recordings.initialize()
            val audio = runCatching { recordings.get(row.audio) }.getOrNull()
            val file = target(row.id)
            val source = if (file.isFile) TranscriptSource.read(file) else null
            val complete = audio?.status == "saved" && source?.audioId == row.audio && !source.discarded && source.completion == TranscriptCompletion.COMPLETE
            val stopped = row.stage == QueueStage.STOPPING || row.stage == QueueStage.PAUSING
            // If shutdown interrupted a manual pause before its text and offset were committed together, offer the partial result and an explicit restart; never replay onto an uncertain prefix.
            row.copy(stage = if (stopped) QueueStage.STOPPED else if (complete) QueueStage.READY else QueueStage.FAILED,
                message = if (complete || row.stage == QueueStage.STOPPING) null else interrupted,
                speakers = if (row.stage == QueueStage.CAPTURING) audio?.captureSpeakerRequest ?: row.speakers else row.speakers).also(journal::update)
        }
    }
}
