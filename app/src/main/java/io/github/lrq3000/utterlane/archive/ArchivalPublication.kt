package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.history.TranscriptHistory

/** Parent-side completion, independently testable with the real repositories. */
class ArchivalPublication(private val journal: ArchivalJobStore, private val audio: RecordingHistory,
    private val text: TranscriptHistory) {
    fun finish(job: ArchivalJob) {
        val accepted = when (job.request.owner.kind) {
            ArtifactKind.AUDIO -> audio.publishArchive(job)
            ArtifactKind.TRANSCRIPT -> text.publishArchive(job)
        }
        if (!accepted) journal.cancel(job.id)
    }
}
