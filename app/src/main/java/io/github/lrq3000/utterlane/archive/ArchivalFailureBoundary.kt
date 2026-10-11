package io.github.lrq3000.utterlane.archive

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.CancellationException

/** Main-confined gate shared by wake, observer and drain work. A failed optional
 * component stays paused until explicit retry, rather than spinning on revisions. */
internal class ArchivalFailureBoundary(private val failed: (Exception) -> Unit) {
    var paused = false
        private set
    private var version = 0L
    fun retry() { version++; paused = false }
    fun fail(error: Exception) {
        if (error is CancellationException) throw error
        if (paused) return
        paused = true // Fence reentrant revisions before invoking any teardown.
        try { failed(error) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (secondary: Exception) { error.addSuppressed(secondary) }
    }
    suspend fun run(action: suspend () -> Unit): Boolean {
        if (paused) return false
        val started = version
        return try { action(); true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (started == version) fail(error)
            false
        }
    }
}

internal suspend fun consumeArchivalWakes(wakes: ReceiveChannel<Unit>, boundary: ArchivalFailureBoundary,
    turn: suspend () -> Unit) {
    for (ignored in wakes) boundary.run(turn)
}
