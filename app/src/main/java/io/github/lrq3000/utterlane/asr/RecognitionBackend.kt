package io.github.lrq3000.utterlane.asr

import java.io.Closeable

data class WindowResult(val tokens: Array<String>, val timestamps: FloatArray, val text: String? = null)

/** Backends expose the same bounded, timestamped contract to every input path. */
interface RecognitionBackend : Closeable {
    /** Load/warm-up is separate so a published candidate can be aborted during preparation. */
    fun prepare() {}
    fun setFailureListener(listener: (String) -> Unit) {}
    fun isAvailable(): Boolean = true
    fun transcribeWindow(samples: ShortArray): WindowResult
    fun transcribeSpeakers(sessionId: Long, window: AudioWindow, count: Int): List<SpeechSpan> = error("This backend does not support streaming diarization")
    fun endSession(sessionId: Long) {}
}
