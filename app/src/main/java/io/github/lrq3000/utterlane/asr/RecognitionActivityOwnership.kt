package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions

/** Accessed under the manager's stateLock, with begin/finish inside its inference mutex. */
internal class RecognitionActivityOwnership {
    class Operation internal constructor(
        val operationOptions: RuntimeOptions,
        val workerOptions: RuntimeOptions,
        val sessionId: Long?
    )

    private var active: Operation? = null

    // The shared worker's diagnostics flag may belong to a different live session.
    // Carry both originals, never splice consent into a falsely labelled worker config.
    fun begin(operationOptions: RuntimeOptions, workerOptions: RuntimeOptions, sessionId: Long? = null): Operation =
        Operation(operationOptions, workerOptions, sessionId).also { active = it }

    fun snapshot(): Operation? = active

    // Identity, rather than value equality, prevents an old finally/close after reset
    // from revoking a newer operation that happens to use identical options.
    fun finish(operation: Operation) { if (active === operation) active = null }
    fun closeSession(id: Long) { if (active?.sessionId == id) active = null }
    fun reset() { active = null }
}
