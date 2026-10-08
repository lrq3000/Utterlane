package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Main-thread handoff from foreground-service preparation to the retained model. */
internal object HomeRetryHandoff {
    fun run(home: MutableStateFlow<HomeState>, retry: () -> Unit, snapshot: () -> TranscriptionDialogState) {
        try {
            // The model enters its Main.immediate viewModelScope here, but the
            // application's Main collector need not have mirrored running yet.
            retry()
        } finally {
            val result = snapshot()
            // Publish the authoritative result and release preparation in ONE
            // update. A Main.immediate service observer must never see an idle
            // gap between those changes. Reading actual state also handles a
            // refused, immediately completed or failed retry without a stuck hold.
            home.update { it.copy(result = result, preparing = false) }
        }
    }
}
