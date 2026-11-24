package io.github.lrq3000.utterlane.transcribe

import android.content.Intent

/** Explicit settings for one imported audio file; absence is reserved for internal callers. */
data class FileTranscriptionOptions(val speakerLabels: Boolean = false) {
    internal fun putInto(intent: Intent): Intent = intent.putExtra(SPEAKERS, speakerLabels)

    companion object {
        private const val SPEAKERS = "file_transcription_speaker_labels"

        internal fun from(intent: Intent): FileTranscriptionOptions? =
            if (intent.hasExtra(SPEAKERS)) FileTranscriptionOptions(intent.getBooleanExtra(SPEAKERS, false)) else null
    }
}
