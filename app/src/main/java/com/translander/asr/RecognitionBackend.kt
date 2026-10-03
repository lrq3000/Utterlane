package com.translander.asr

import java.io.Closeable

data class WindowResult(val tokens: Array<String>, val timestamps: FloatArray)

/** Backends expose the same bounded, timestamped contract to every input path. */
interface RecognitionBackend : Closeable {
    /** Load/warm-up is separate so a published candidate can be aborted during preparation. */
    fun prepare() {}
    fun setFailureListener(listener: (String) -> Unit) {}
    fun isAvailable(): Boolean = true
    fun transcribeWindow(samples: ShortArray): WindowResult
}
