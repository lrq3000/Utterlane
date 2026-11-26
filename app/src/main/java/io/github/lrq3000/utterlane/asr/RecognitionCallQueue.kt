package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job

/** One nonpreemptible model operation at a time. Priority belongs to a ready call, never to an entire recording. */
internal class RecognitionCallQueue {
    internal class Turn(val capture: CaptureAdmission.Lease?, private val worker: CoroutineScope) {
        internal val ready = CompletableDeferred<Unit>()
        private var reply: Deferred<*>? = null

        /** The selected turn owns native dispatch and its captured inputs. Delete/Back cancels only await; reset/worker exit resolves the outstanding operation. */
        suspend fun <T> awaitNativeReply(call: suspend () -> T): T {
            val caller = currentCoroutineContext().job
            check(reply?.isCompleted != false) { "A native call is already running in this turn" }
            val operation = worker.async { caller.ensureActive(); call() }
            reply = operation
            return operation.await()
        }

        internal fun whenReleasable(release: () -> Unit) {
            val operation = reply
            if (operation == null || operation.isCompleted) release() else operation.invokeOnCompletion { release() }
        }
    }

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var owner: Turn? = null
    private val waiting = LinkedHashSet<Turn>()
    // Live can only become false (Stop/EOF). Keep the original arrival order in waiting so demotion costs no requeue or lost position.
    private val live = LinkedHashSet<Turn>()

    suspend fun <T> withTurn(capture: CaptureAdmission.Lease? = null, call: suspend Turn.() -> T): T {
        val turn = Turn(capture, worker)
        val queued = synchronized(this) {
            if (owner == null) { owner = turn; false } else {
                waiting.add(turn)
                if (capture?.isLive == true) live.add(turn)
                true
            }
        }
        try {
            if (queued) turn.ready.await()
            currentCoroutineContext().ensureActive()
            return turn.call()
        } finally {
            turn.whenReleasable { release(turn) }
        }
    }

    private fun release(turn: Turn) {
        val next = synchronized(this) {
            // Only choose the next owner at the actual reply boundary, not when a cancelled caller stops listening.
            waiting.remove(turn)
            live.remove(turn)
            if (owner === turn) selectNext().also { owner = it } else null
        }
        next?.ready?.complete(Unit)
    }

    private fun selectNext(): Turn? {
        val candidates = live.iterator()
        while (candidates.hasNext()) {
            val turn = candidates.next()
            candidates.remove()
            if (turn.capture?.isLive == true) { waiting.remove(turn); return turn }
        }
        return waiting.firstOrNull()?.also { waiting.remove(it) }
    }
}
