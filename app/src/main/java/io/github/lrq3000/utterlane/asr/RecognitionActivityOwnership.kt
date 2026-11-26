package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

/** Operation and diagnostic ownership are managed under stateLock. Presenter subscriptions have independent lifetimes. */
internal class RecognitionActivityOwnership {
    class Operation internal constructor(
        val operationOptions: RuntimeOptions,
        val workerOptions: RuntimeOptions,
        val sessionId: Long?,
        internal val progress: MutableStateFlow<RecognitionActivity>?
    )

    private var active: Operation? = null
    private val presenters = IdentityHashMap<Any, MutableStateFlow<RecognitionActivity>>()

    fun observe(owner: Any, scope: CoroutineScope, sink: (RecognitionActivity) -> Unit): Job {
        val progress = MutableStateFlow(RecognitionActivity())
        synchronized(presenters) { presenters[owner] = progress }
        return scope.launch { progress.collect { sink(it) } }.also { job ->
            job.invokeOnCompletion { synchronized(presenters) { presenters.remove(owner, progress) } }
        }
    }

    fun publish(activity: RecognitionActivity) { active?.progress?.value = activity }

    // The shared worker's diagnostics flag may belong to a different live session.
    // Carry both originals, never splice consent into a falsely labelled worker config.
    fun begin(operationOptions: RuntimeOptions, workerOptions: RuntimeOptions, sessionId: Long? = null, progressOwner: Any? = null): Operation =
        Operation(operationOptions, workerOptions, sessionId, synchronized(presenters) { presenters[progressOwner] }).also { active = it }

    fun snapshot(): Operation? = active

    // Identity, rather than value equality, prevents an old finally/close after reset
    // from revoking a newer operation that happens to use identical options.
    fun finish(operation: Operation) {
        if (active !== operation) return
        operation.progress?.let { if (it.value.active) it.value = it.value.copy(active = false) }
        active = null
    }
    fun closeSession(id: Long) { active?.takeIf { it.sessionId == id }?.let(::finish) }
    fun reset() { active?.let(::finish) }
}
