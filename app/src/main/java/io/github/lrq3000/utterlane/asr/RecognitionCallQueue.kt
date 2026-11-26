package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One nonpreemptible model operation at a time. Priority belongs to a ready call, never to an entire recording. */
internal class RecognitionCallQueue {
    private class Turn(val capture: CaptureAdmission.Lease?) {
        val ready = CompletableDeferred<Unit>()
    }

    private var owner: Turn? = null
    private val waiting = LinkedHashSet<Turn>()
    // Live can only become false (Stop/EOF). Keep the original arrival order in waiting so demotion costs no requeue or lost position.
    private val live = LinkedHashSet<Turn>()

    suspend fun <T> withTurn(capture: CaptureAdmission.Lease? = null, call: suspend () -> T): T {
        val turn = Turn(capture)
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
            return call()
        } finally {
            val next = synchronized(this) {
                // Cancellation can win while waiting, or after selection but before await resumes. Only the selected owner passes the baton.
                waiting.remove(turn)
                live.remove(turn)
                if (owner === turn) selectNext().also { owner = it } else null
            }
            // Completing may resume user code immediately on an unconfined dispatcher; never do it inside the monitor.
            next?.ready?.complete(Unit)
        }
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
