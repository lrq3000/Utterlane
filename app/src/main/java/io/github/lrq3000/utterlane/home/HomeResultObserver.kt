package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Observes actual model starts, including retries initiated by the reused dialog. */
internal class HomeResultObserver(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    fun observe(home: MutableStateFlow<HomeState>, results: StateFlow<TranscriptionDialogState>,
        isCurrent: () -> Boolean, onStarted: () -> Unit,
        onResult: (TranscriptionDialogState, Boolean) -> Unit): Job = scope.launch(dispatcher) {
        var wasRunning = false
        results.collect { result ->
            if (!isCurrent()) return@collect
            val started = result.running && !wasRunning
            // Hydration is not a retry. Preserve capture failures while importing,
            // but retire the old capture/permission error when the model actually
            // starts new work. Later progress must not erase a new service error.
            home.update { it.copy(result = result,
                message = if (started) null else it.message,
                permissionDenied = if (started) false else it.permissionDenied) }
            // Main.immediate observes a dialog's Main.immediate model start before
            // its UI callback returns, so permission-free FGS startup is not left
            // to a later app-scope collector or background onDestroy callback.
            if (started) onStarted()
            onResult(result, wasRunning)
            wasRunning = result.running
        }
    }
}
