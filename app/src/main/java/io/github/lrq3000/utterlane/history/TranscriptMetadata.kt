package io.github.lrq3000.utterlane.history

import android.os.Bundle
import java.io.File

/** A result owns this small snapshot independently of its source's lifetime.
 * Saving text never needs to reread audio or scan the transcript for labels. */
data class TranscriptMetadata(val created: Long? = null, val durationMs: Long = 0, val speakerLabels: Boolean = false) {
    companion object {
        private const val STATE_KEY = "result_metadata"

        /** Missing metadata is an older checkpoint, not a fabricated timestamp. */
        fun fromBundle(state: Bundle?): TranscriptMetadata? = state?.getBundle(STATE_KEY)?.let {
            TranscriptMetadata(if (it.containsKey("created")) it.getLong("created") else null,
                it.getLong("durationMs", 0), it.getBoolean("speakerLabels", false))
        }
    }

    constructor(audio: HistoryEntry, durationMs: Long = audio.durationMs, speakerLabels: Boolean = audio.speakerLabels) :
        this(audio.started, durationMs, speakerLabels)

    constructor(text: TranscriptEntry) : this(text.created, text.durationMs, text.speakerLabels)

    /** A bounded checkpoint independent of audio/history availability. Share this
     * codec between Activity restoration and Home journals rather than their keys. */
    fun writeToBundle(out: Bundle) {
        out.putBundle(STATE_KEY, Bundle().apply {
            created?.let { putLong("created", it) }
            putLong("durationMs", durationMs)
            putBoolean("speakerLabels", speakerLabels)
        })
    }

    fun save(history: TranscriptHistory, source: File, model: String, audioId: String? = null,
        pinned: Boolean = false, modelId: String? = null): TranscriptEntry =
        history.save(source, model, audioId, pinned, modelId = modelId,
            created = created, durationMs = durationMs, speakerLabels = speakerLabels)
}
