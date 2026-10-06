package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The session's option-selection and inference-queue boundary, without Android dependencies. */
internal class RecognitionSessionPreparation(
    private val mutex: Mutex,
    private val readOptions: suspend () -> RuntimeOptions
) {
    suspend fun <T> prepare(provided: RuntimeOptions?, block: suspend (RuntimeOptions) -> T): T {
        // Select exactly once before the inference wait. An entry point's snapshot is
        // authoritative even if preferences changed before this call or while queued.
        // The manager's outer operation reservation covers this read and the whole wait.
        val snapshot = (provided ?: readOptions()).requireValid()
        return mutex.withLock { block(snapshot) }
    }
}
