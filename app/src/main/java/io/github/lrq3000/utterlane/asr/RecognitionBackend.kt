package io.github.lrq3000.utterlane.asr

import java.io.Closeable
import io.github.lrq3000.utterlane.settings.RuntimeOptions

data class WindowResult(val tokens: Array<String>, val timestamps: FloatArray, val text: String? = null,
                        val ends: FloatArray = floatArrayOf())

/** Backends expose the same bounded, timestamped contract to every input path. */
interface RecognitionBackend : Closeable {
    /** Load/warm-up is separate so a published candidate can be aborted during preparation. */
    fun prepare() {}
    fun configure(options: RuntimeOptions) {}
    /** Cumulative completed units per invocation/stage; opaque backends keep the no-op. */
    fun setProgressListener(listener: (Long, String) -> Unit) {}
    fun setActivityListener(listener: (RecognitionActivity) -> Unit) {}
    fun setFailureListener(listener: (String) -> Unit) {}
    fun isAvailable(): Boolean = true
    fun transcribeWindow(samples: ShortArray): WindowResult
    fun transcribeSpeakers(sessionId: Long, window: AudioWindow, count: Int, options: RuntimeOptions = RuntimeOptions()): List<SpeechSpan> = error("This backend does not support streaming diarization")
    /** Drain existing speaker state only; never recognize or load a model at EOF. */
    fun finishSpeakers(sessionId: Long, options: RuntimeOptions = RuntimeOptions()): List<SpeechSpan> = emptyList()
    fun endSession(sessionId: Long) {}
}
