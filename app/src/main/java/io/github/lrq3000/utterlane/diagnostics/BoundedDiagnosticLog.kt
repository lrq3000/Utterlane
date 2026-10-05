package io.github.lrq3000.utterlane.diagnostics

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Single writer; trySend never waits for disk, export, a full queue or a native call. */
internal class BoundedDiagnosticLog(
    private val files: DiagnosticStorage,
    metadata: () -> DiagnosticEnvironment,
    capacity: Int = 64,
    private val onFailure: () -> Unit = {}
) {
    private sealed interface Command {
        class Append(val record: DiagnosticRecord, val timestamp: Long) : Command
        class Snapshot(val reply: CompletableDeferred<File>) : Command
        class Clear(val reply: CompletableDeferred<Unit>) : Command
    }
    private val commands = Channel<Command>(capacity.also { require(it in 1..256) })
    private val dropped = AtomicLong()
    val droppedRecords: Long get() = dropped.get()
    private val failureReported = AtomicBoolean()
    private val environment by lazy(metadata)
    private val runId = UUID.randomUUID().toString()
    private val writer = CoroutineScope(Dispatchers.IO).launch {
        try { files.prune() } catch (_: Exception) { reportFailure() }
        for (command in commands) {
            try {
                when (command) {
                    is Command.Append -> {
                        val bytes = command.record.encode(environment, runId, command.timestamp, dropped.get())
                        if (bytes.size > 8192 || !files.append(bytes)) dropped.incrementAndGet()
                    }
                    is Command.Snapshot -> command.reply.complete(files.snapshot())
                    is Command.Clear -> { files.clear(); dropped.set(0); failureReported.set(false); command.reply.complete(Unit) }
                }
            } catch (failure: Exception) {
                reportFailure()
                when (command) {
                    is Command.Append -> dropped.incrementAndGet()
                    is Command.Snapshot -> command.reply.completeExceptionally(failure)
                    is Command.Clear -> command.reply.completeExceptionally(failure)
                }
            }
        }
    }

    fun offer(record: DiagnosticRecord): Boolean {
        if (!record.options.diagnostics) return false
        // Invalid/unbounded configuration strings are rejected before entering the queue.
        if (listOf(record.options, record.configuration).any {
            it.diarizationMode.length > 32 || it.validationErrors().isNotEmpty()
        }) return false
        val accepted = commands.trySend(Command.Append(record, System.nanoTime() / 1000000)).isSuccess
        if (!accepted) dropped.incrementAndGet()
        return accepted
    }

    suspend fun snapshot(): File {
        val reply = CompletableDeferred<File>()
        commands.send(Command.Snapshot(reply)) // Suspends the caller if full; never blocks its UI thread.
        return reply.await()
    }
    suspend fun clear() {
        val reply = CompletableDeferred<Unit>()
        commands.send(Command.Clear(reply))
        reply.await()
    }
    suspend fun close() { commands.close(); writer.join() }
    private fun reportFailure() { if (failureReported.compareAndSet(false, true)) onFailure() }
}
