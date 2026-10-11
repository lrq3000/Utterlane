package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.RecordingHistory

/** Parent-only transition; caller has already stopped/quiesced the codec worker. */
internal class EncodingFailureRecovery(private val journal: ArchivalJobStore, private val history: RecordingHistory) {
    fun afterWorkerStopped(job: ArchivalJob, message: String) {
        require(job.request.operation == ArchivalOperation.ENCODE_AUDIO)
        // Stop-all can win while a codec error is travelling back to the parent.
        // Never borrow a newer plan/epoch to turn that cancellation into copies.
        if (journal.currentEpoch() != job.epoch || journal.isDeleted(job.request.owner)) return
        val current = journal.job(job.id) ?: return
        if (current.generation != job.generation || current.state !in setOf(ArchivalState.RUNNING, ArchivalState.PAUSED)) return
        if (journal.activeRepresentation(job.request.owner) != job.request.sourceRepresentation) return
        // Never make the job terminal before its replacement work is durable.
        // PAUSED plus this persisted phase is visible to the ordinary Retry sweep,
        // including when the following metadata repair fails or the parent dies.
        val pending = journal.pendingFallback(job.id, job.generation, message) ?: return
        history.stopEncoding(job.request.owner.id, message)
        if (history.scheduleEncodingFallback(pending)) journal.completeFallback(pending.id, pending.generation)
    }
}
