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
                var validation: Validation
                do {
                    if (!isCurrent()) return@first true
                    validation = withContext(ioDispatcher) { validateCurrent(results, isCurrent, onAccepted) }
                    if (!isCurrent() || validation.promoted) return@first true
                    // A copy can complete (and decoding fail) before this Main
                    // continuation runs. Never combine its new terminal flags
                    // with a negative ownership check of an earlier snapshot.
                    // Retry here rather than relying on another StateFlow emission:
                    // conflation can hide a transition back to equal state.
                } while (results.value !== validation.snapshot)
                val checked = validation.snapshot
                if (!checked.importing && !checked.running) { onRejected(checked.message); true }
                else false
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { if (isCurrent()) onRejected(error.message) }
    }

    private data class Validation(val snapshot: TranscriptionDialogState, val promoted: Boolean)

    private fun validateCurrent(results: StateFlow<TranscriptionDialogState>, isCurrent: () -> Boolean,
        onAccepted: () -> Unit): Validation = transcripts.withPublicationLock {
        val snapshot = results.value
        Validation(snapshot, promoteSnapshot(snapshot, results, isCurrent, onAccepted))
    }

    /** Called only inside validateCurrent's publication guard. Negative decisions
     * remain tied to its snapshot; successful publication still uses current state. */
    private fun promoteSnapshot(snapshot: TranscriptionDialogState, results: StateFlow<TranscriptionDialogState>,
        isCurrent: () -> Boolean, onAccepted: () -> Unit): Boolean {
        if (!isCurrent()) return false
        // Match confirmed deletion's lock order: transcript history, then audio
        // history or working-source disposition. All filesystem checks stay on IO.
        val audioId = snapshot.audio?.id
        if (recordings.withCompletedImport(audioId) {
            if (!isCurrent() || results.value.audio?.id != audioId) false
            else { onAccepted(); true }
        }) return true

        val store = snapshot.store ?: return false
        return try {
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
