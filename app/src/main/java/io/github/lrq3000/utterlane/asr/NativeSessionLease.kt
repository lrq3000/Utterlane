package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.CancellationException

/** A caller can close its transcript immediately, but END_SESSION must follow the last native reply, including a reply its caller abandoned. */
internal class NativeSessionLease(private val release: () -> Unit) {
    private var inUse = false
    private var closed = false

    fun <T> useBackend(call: () -> T): T {
        synchronized(this) {
            if (closed) throw CancellationException("Transcription session closed")
            check(!inUse)
            inUse = true
        }
        return try { call() } finally {
            if (synchronized(this) { inUse = false; closed }) release()
        }
    }

    fun close() {
        val releaseNow = synchronized(this) {
            if (closed) return
            closed = true
            !inUse
        }
        if (releaseNow) release()
    }
}
