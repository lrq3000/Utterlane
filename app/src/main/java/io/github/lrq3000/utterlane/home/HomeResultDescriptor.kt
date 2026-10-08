package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.transcribe.DialogInput

/** Converts an asynchronously hydrated dialog snapshot into a recovery checkpoint. */
internal class HomeResultDescriptor(input: DialogInput) {
    private var current = input.copy(uri = null, path = null, automatic = false)
    private val needsTextHydration = input.transcriptPath != null || input.transcriptId != null
    private var hydrating = true

    fun update(snapshot: DialogInput, importing: Boolean): DialogInput {
        // importing is also reset by the model's failure finally block. When the
        // input already describes text, require an actual working store before
        // interpreting null identifiers as resolved ownership rather than a read
        // that has not happened (or has failed). Explicit dismissal clears the
        // journal separately and never goes through this fallback.
        if (!importing && (!needsTextHydration || snapshot.transcriptPath != null)) hydrating = false
        current = if (hydrating) snapshot.copy(
            audioId = snapshot.audioId ?: current.audioId,
            transcriptId = snapshot.transcriptId ?: current.transcriptId,
            transcriptPath = snapshot.transcriptPath ?: current.transcriptPath
        ) else snapshot
        // After hydration, accept nulls as-is: a new unsaved retry must not be
        // journaled with the previous attempt's saved ID or working-text path.
        return current
    }
}
