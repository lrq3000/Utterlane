package io.github.lrq3000.utterlane.asr

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Only a final reply removes a waiter. Progress and status packets never consume it. */
internal class RecognitionRequests<T> {
    class Pending<T>(val watchdog: ProgressWatchdog) { val result = CompletableFuture<T>() }
    private val pending = ConcurrentHashMap<Int, Pending<T>>()

    fun register(id: Int, watchdog: ProgressWatchdog): Pending<T> = Pending<T>(watchdog).also {
        check(pending.putIfAbsent(id, it) == null) { "Duplicate recognition request" }
    }
    fun progress(packet: RecognitionProgress, now: Long, paused: Boolean): Boolean =
        pending[packet.requestId]?.watchdog?.accept(packet, now, paused) ?: false
    fun complete(id: Int, result: T): Boolean = pending.remove(id)?.result?.complete(result) ?: false
    fun remove(id: Int) { pending.remove(id) }
    fun fail(error: Throwable) { pending.values.forEach { it.result.completeExceptionally(error) } }
}
