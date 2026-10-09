package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptDiscardedException
import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.history.TranscriptHistory
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogModel
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Validate and commit one candidate on IO; repository/source guards make the
 * ownership transfer indivisible with in-app deletion. UI cleanup follows on Main. */
internal class HomeFileHandoff(
    private val scope: CoroutineScope,
    private val recordings: RecordingHistory,
    private val transcripts: TranscriptHistory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    fun observe(results: StateFlow<TranscriptionDialogState>, isCurrent: () -> Boolean,
        onAccepted: () -> Unit, onRejected: (String?) -> Unit): Job = scope.launch(dispatcher) {
        try {
            // first() retires this temporary observer on either outcome. It never
            // mirrors candidate state into Home or writes the recovery journal.
            results.first {
                if (!isCurrent()) return@first true
                val promoted = withContext(ioDispatcher) { promoteCurrent(results, isCurrent, onAccepted) }
                val current = results.value
                if (!isCurrent()) true
                else if (promoted) true
                else if (!current.importing && !current.running) { onRejected(current.message); true }
                else false
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { if (isCurrent()) onRejected(error.message) }
    }

    private fun promoteCurrent(results: StateFlow<TranscriptionDialogState>, isCurrent: () -> Boolean,
        onAccepted: () -> Unit): Boolean = transcripts.withPublicationLock {
        if (!isCurrent()) return@withPublicationLock false
        // Match confirmed deletion's lock order: transcript history, then audio
        // history or working-source disposition. All filesystem checks stay on IO.
        val audioId = results.value.audio?.id
        if (recordings.withCompletedImport(audioId) {
            if (!isCurrent() || results.value.audio?.id != audioId) false
            else { onAccepted(); true }
        }) return@withPublicationLock true

        val store = results.value.store ?: return@withPublicationLock false
        try {
            TranscriptSource.withActiveSource(store.file) {
                val current = results.value
                if (!isCurrent() || current.store !== store || current.transcriptBytes == 0L ||
                    current.preview.isBlank() || !store.file.isFile || store.bytes == 0L) false
                else { onAccepted(); true }
            }
        } catch (_: TranscriptDiscardedException) { false }
    }

    companion object {
        fun publish(home: MutableStateFlow<HomeState>, model: TranscriptionDialogModel?,
            results: StateFlow<TranscriptionDialogState>) {
            // Acceptance IO can outlive an importing->running transition. Read
            // current model state now and release preparation in this one update.
            home.update { it.copy(model = model, result = results.value, preparing = false,
                message = null, permissionDenied = false) }
        }
    }
}
