package io.github.lrq3000.utterlane.archive

import java.io.Closeable
import kotlinx.coroutines.*

/** Parent execution ownership. Claim, source lease and dispatch all belong to
 * the same cleanup region; the worker itself is stopped on the caller dispatcher. */
internal class ArchivalExecution(private val journal: ArchivalJobStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    class Claim internal constructor(private val io: CoroutineDispatcher) {
        internal var acquired: ArchivalJob? = null
        val job: ArchivalJob get() = checkNotNull(acquired)
        internal var lease: Closeable? = null
        var dispatched = false
        var quiescent = false
        suspend fun acquireLease(acquire: () -> Closeable) { withContext(io) { lease = acquire() } }
    }

    suspend fun runNext(quiesce: (ArchivalJob) -> Unit, released: (ArchivalJob) -> Unit,
        cleanupFailed: (Exception) -> Unit, execute: suspend (Claim) -> Unit): Boolean {
        val claim = Claim(io)
        var primary: Throwable? = null
        try {
            // Publish ownership from inside IO, within the cleanup region. A
            // cancelled return dispatch may discard a value, but not this holder.
            withContext(io) { claim.acquired = journal.claim() }
            if (claim.acquired == null) return false
            execute(claim)
            return true
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            claim.acquired?.let { job ->
                var cleanupError: Exception? = null
                fun attempt(action: () -> Unit) {
                    try { action() } catch (error: Exception) {
                        val previous = cleanupError
                        if (previous == null) cleanupError = error else previous.addSuppressed(error)
                    }
                }
                if (claim.dispatched && !claim.quiescent) attempt { quiesce(job) }
                try { withContext(NonCancellable + io) {
                    if (!claim.dispatched) attempt { journal.notCreated(job.id, job.generation) }
                    // Also settles plain coroutine cancellation without a prior
                    // supervisor stop. Generation checks exclude newer attempts.
                    attempt { journal.interruptExecution(job.id, job.generation) }
                    attempt { claim.lease?.close() }
                    attempt { journal.releaseExecution(job.id, job.generation) }
                } } catch (error: Exception) { attempt { throw error } }
                attempt { released(job) }
                cleanupError?.let { error ->
                    val original = primary
                    if (original == null) throw error
                    original.addSuppressed(error)
                    if (error !is CancellationException) {
                        try { cleanupFailed(error) } catch (reporting: Exception) { original.addSuppressed(reporting) }
                    }
                }
            }
        }
    }
}
