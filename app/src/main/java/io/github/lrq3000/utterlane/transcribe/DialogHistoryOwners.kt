package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.history.TranscriptHistory
import java.io.Closeable

/** Keep currently displayed history pinnable when its timer expires. These
 * process-local leases never defeat explicit deletion or survive process death.
 * Calls and close run on IO, independently of Android Activity recreation. */
internal class DialogHistoryOwners(audio: RecordingHistory, text: TranscriptHistory) : Closeable {
    private class Owner(private val acquire: (String) -> Closeable) : Closeable {
        private var id: String? = null
        private var lease: Closeable? = null
        private var closed = false
        @Synchronized fun show(value: String?, isCurrent: () -> Boolean) {
            if (closed || !isCurrent() || id == value) return
            lease?.close(); lease = null; id = null
            if (value != null) {
                // A concurrent explicit deletion is allowed to win acquisition.
                try { lease = acquire(value); id = value } catch (_: IllegalStateException) { }
            }
        }
        @Synchronized override fun close() { closed = true; lease?.close(); lease = null; id = null }
    }
    private val audioOwner = Owner { audio.acquire(it, protectFromPruning = true) }
    private val textOwner = Owner { text.acquire(it, protectFromPruning = true) }
    fun audio(id: String?, isCurrent: () -> Boolean = { true }) = audioOwner.show(id, isCurrent)
    fun transcript(id: String?, isCurrent: () -> Boolean = { true }) = textOwner.show(id, isCurrent)
    override fun close() { try { audioOwner.close() } finally { textOwner.close() } }
}
