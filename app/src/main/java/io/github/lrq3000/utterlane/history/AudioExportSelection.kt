package io.github.lrq3000.utterlane.history

import java.io.Closeable
import java.io.OutputStream

/** Metadata and payload identity for one user export/share operation. */
class AudioExportSelection internal constructor(internal val history: RecordingHistory, val entry: HistoryEntry,
    private val lease: Closeable) : Closeable {
    val mimeType = if (entry.parts == 1) entry.asset(0).mimeType else "audio/*"
    val name = entry.asset(0).name
    val representation = entry.representation
    val parts = entry.parts
    private var closed = false
    fun part(index: Int): AudioPart { check(!closed); return entry.asset(index) }
    fun copyPartTo(index: Int, output: OutputStream) {
        check(!closed)
        history.openPart(entry, index).use { it.copyTo(output, 65536) }
    }
    override fun close() { if (!closed) { closed = true; lease.close() } }
}

/** Retained by a ViewModel across Activity recreation. A new process has no lease
 * and therefore cannot silently replace an old picker selection with new bytes. */
class AudioExportSession : Closeable {
    private var selected: AudioExportSelection? = null
    private var closed = false
    @Synchronized fun install(selection: AudioExportSelection) {
        check(!closed && selected == null) { "An audio destination selection is already pending" }
        selected = selection
    }
    @Synchronized fun take(): AudioExportSelection = checkNotNull(selected) {
        "The original export selection is no longer available. Choose Save audio again."
    }.also { selected = null }
    fun cancel() { val old = synchronized(this) { selected.also { selected = null } }; old?.close() }
    override fun close() { synchronized(this) { closed = true }; cancel() }
}
