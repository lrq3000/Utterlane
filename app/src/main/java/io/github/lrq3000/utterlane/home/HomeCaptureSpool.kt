package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.history.RecordingHistory

/** IO-only evidence that the microphone writer has published recoverable PCM. */
internal class HomeCaptureSpool(private val history: RecordingHistory) {
    fun hasPublishedSamples(id: String): Boolean = runCatching {
        val entry = history.get(id)
        // Active entry.samples remains zero until finish. The existing PCM read
        // API instead gates reads on Recording.writtenSamples, published AFTER
        // WavFile.append completes. Read just one sample: constant memory/IO and
        // positive evidence of recoverable data, not speculative recorder metrics.
        entry.status != "discarded" && entry.sourceName == null && if (entry.rollingAudio != null) {
            if (entry.rollingAudio.complete) return@runCatching history.available(entry)
            val spool = io.github.lrq3000.utterlane.archive.RollingPcmJournal.open(entry.directory)
            val state = spool.state
            state.reclaimedFrames > 0 || spool.readFrames(0, 1).isNotEmpty()
        } else history.read(id, 0, 1).isNotEmpty()
    }.getOrDefault(false)
}
