package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.history.AudioImportMetadata
import io.github.lrq3000.utterlane.history.ImportRetentionChoice
import io.github.lrq3000.utterlane.history.RecordingHistory
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException

/** Adopt the completed private source from an interrupted queue publication; a partial copy is removed by history initialization and cannot be adopted. */
internal fun RecordingHistory.stagedImport(id: String): RecordingHistory.OwnedImport? = synchronized(this) {
    initialize()
    val entry = runCatching { get(id) }.getOrNull()?.takeIf { it.importMetadata != null } ?: return null
    RecordingHistory.OwnedImport(entry, acquire(id, true))
}

/** Shared source-close / owned-copy boundary, independent of Android providers. */
internal fun copyImportedAudio(history: RecordingHistory, source: InputStream, extension: String,
    metadata: AudioImportMetadata, retention: ImportRetentionChoice, speakerLabels: Boolean? = null, importId: String? = null): RecordingHistory.OwnedImport {
    // runInterruptible can stop large local copies between read operations.
    val input = object : FilterInputStream(source) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio import cancelled")
            return super.read(buffer, offset, length)
        }
    }
    var owned: RecordingHistory.OwnedImport? = null
    try {
        input.use { owned = history.importAudioOwned(it, extension, metadata, retention, speakerLabels, importId) }
        return checkNotNull(owned)
    } catch (error: Throwable) {
        // Source close happens after publication, but before the caller receives
        // the handle. Preserve the usable copy for recovery, while releasing the
        // otherwise unreachable protected lease. Keep the original failure even
        // if local lease cleanup also reports an error.
        try { owned?.lease?.close() }
        catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
        throw error
    }
}
