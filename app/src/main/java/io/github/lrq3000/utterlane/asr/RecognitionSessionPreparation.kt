package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions

/** The session's option-selection and inference-queue boundary, without Android dependencies. */
internal class RecognitionSessionPreparation(
    private val queue: RecognitionCallQueue,
    private val readOptions: suspend () -> RuntimeOptions
) {
    suspend fun <T> prepare(provided: RuntimeOptions?, capture: CaptureAdmission.Lease? = null, block: suspend RecognitionCallQueue.Turn.(RuntimeOptions) -> T): T {
        // Select exactly once before the inference wait. An entry point's snapshot is
        // authoritative even if preferences changed before this call or while queued.
        // The manager's outer operation reservation covers this read and the whole wait.
        val snapshot = (provided ?: readOptions()).requireValid()
        return queue.withTurn(capture) { block(snapshot) }
    }
}
