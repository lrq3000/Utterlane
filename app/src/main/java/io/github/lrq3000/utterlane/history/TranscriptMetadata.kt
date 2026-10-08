package io.github.lrq3000.utterlane.history

import java.io.File

/** A result owns this small snapshot independently of its source's lifetime.
 * Saving text never needs to reread audio or scan the transcript for labels. */
data class TranscriptMetadata(val created: Long? = null, val durationMs: Long = 0, val speakerLabels: Boolean = false) {
    constructor(audio: HistoryEntry, durationMs: Long = audio.durationMs, speakerLabels: Boolean = audio.speakerLabels) :
        this(audio.started, durationMs, speakerLabels)

    constructor(text: TranscriptEntry) : this(text.created, text.durationMs, text.speakerLabels)

    fun save(history: TranscriptHistory, source: File, model: String, audioId: String? = null,
        pinned: Boolean = false, modelId: String? = null): TranscriptEntry =
        history.save(source, model, audioId, pinned, modelId = modelId,
            created = created, durationMs = durationMs, speakerLabels = speakerLabels)
}
