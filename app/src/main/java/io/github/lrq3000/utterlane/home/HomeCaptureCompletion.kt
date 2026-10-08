package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.history.HistoryEntry

/** Decides whether a finalized attempt owns input that can replace the prior workspace. */
internal class HomeCaptureCompletion<R>(
    private val onResult: (R) -> Unit,
    private val onEmpty: (String) -> Unit
) {
    fun deliver(result: R, audio: HistoryEntry?, preview: String, failureMessage: String): Boolean {
        // MicrophoneSession allocates an ID before opening AudioRecord and keeps
        // that ID after its finalizer deletes a zero-sample attempt. Only finalized
        // retained samples or actual text can acknowledge the previous workspace.
        val retainedAudio = audio != null && audio.status != "discarded" && audio.samples > 0
        if (!retainedAudio && preview.isBlank()) {
            onEmpty(failureMessage)
            return false
        }
        onResult(result)
        return true
    }
}
