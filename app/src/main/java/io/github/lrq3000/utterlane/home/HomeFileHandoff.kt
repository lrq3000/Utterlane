package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogModel
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Main-thread handoff of one already-created local-file result owner. */
internal class HomeFileHandoff(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    fun observe(results: StateFlow<TranscriptionDialogState>, isCurrent: () -> Boolean,
        onAccepted: () -> Unit, onRejected: (String?) -> Unit): Job = scope.launch(dispatcher) {
        try {
            // first() retires this temporary observer on either outcome. It never
            // mirrors candidate state into Home or writes the recovery journal.
            results.first { result ->
                if (!isCurrent()) return@first true
                val owned = withContext(ioDispatcher) { hasOwnedInput(result) }
                if (!isCurrent()) true
                else if (owned) { onAccepted(); true }
                else if (!result.importing && !result.running) { onRejected(result.message); true }
                else false
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { if (isCurrent()) onRejected(error.message) }
    }

    private fun hasOwnedInput(result: TranscriptionDialogState): Boolean {
        val store = result.store
        if (store != null && result.transcriptBytes > 0 && result.preview.isNotBlank() &&
            store.file.isFile && !TranscriptSource.read(store.file).discarded) return true
        val audio = result.audio ?: return false
        // A URI or allocated ID does not prove ownership. Only the completed,
        // private, nonempty copy can replace prior work. Decodability and model
        // availability are deliberately irrelevant: that input can be retried.
        return audio.sourceName != null && audio.status !in setOf("importing", "active", "discarded") &&
            audio.part(0).isFile && audio.part(0).length() > 0
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
