package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.MicrophonePipeline
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared audio ownership, separate from the success/failure of any particular model. */
class MicrophoneRecordings(private val history: RecordingHistory, private val temporaryRoot: File) {
    // Constructed only on IO. Previous-process temporary audio has no live recovery
    // owner; clean it before publishing any new handle, never in a racing startup job.
    private val temporaryHistory by lazy {
        temporaryRoot.listFiles()?.forEach { check(it.deleteRecursively()) { "Cannot remove abandoned temporary audio" } }
        RecordingHistory(temporaryRoot)
    }
    private val recoveries = linkedMapOf<String, Recording>()
    private val mutablePending = MutableStateFlow<List<String>>(emptyList())
    val pending: StateFlow<List<String>> = mutablePending

    fun initializeTemporaryStorage() { temporaryHistory }

    fun begin(retention: HistoryRetention): Recording {
        val temporary = retention == HistoryRetention.NONE
        val repository = if (temporary) temporaryHistory else history
        val recording = checkNotNull(repository.begin(if (temporary) HistoryRetention.FOREVER else retention))
        return Recording(repository, recording, temporary)
    }

    @Synchronized fun recover(recording: Recording, message: String? = null, modelFailure: Boolean = false): String {
        val id = recording.entry.id
        recording.failureMessage = message
        recording.modelFailure = modelFailure
        recoveries[id] = recording
        // History retention and explicit deletion remain effective while an error
        // screen waits. A retry obtains its own reader lease before touching data.
        if (!recording.temporary) recording.close()
        mutablePending.value = recoveries.keys.toList()
        return id
    }

    @Synchronized fun get(id: String): Recording = checkNotNull(recoveries[id]) { "Recording recovery is unavailable" }

    fun discard(id: String) {
        val recording = synchronized(this) {
            recoveries.remove(id).also { mutablePending.value = recoveries.keys.toList() }
        }
        // Removing large WAV parts must not hold the registry lock needed by UI reads.
        recording?.close()
    }

    class Recording internal constructor(
        private val repository: RecordingHistory,
        private val writer: RecordingHistory.Recording,
        val temporary: Boolean
    ) : MicrophonePipeline.Audio, Closeable {
        private val owner = repository.acquire(writer.entry.id)
        private val closed = AtomicBoolean(false)
        var failureMessage: String? = null
            internal set
        var modelFailure: Boolean = false
            internal set
        // Identity remains usable for dismissing an already-expired history item.
        // Sample counts come from the writer, not this initial metadata snapshot.
        val entry: HistoryEntry = writer.entry
        override val samples: Long get() = writer.writtenSamples
        override fun append(samples: ShortArray) = writer.append(samples)
        override fun read(offset: Long, count: Int): ShortArray = repository.read(writer.entry.id, offset, count)
        fun finish(failed: Boolean) = writer.finish(failed)
        fun acquire(): Closeable = repository.acquire(writer.entry.id)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { if (temporary) repository.delete(writer.entry.id) }
            finally { owner.close() }
        }
    }
}
