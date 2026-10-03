package com.translander.asr

import java.io.Closeable

data class WindowResult(val tokens: Array<String>, val timestamps: FloatArray)

/** Backends expose the same bounded, timestamped contract to every input path. */
interface RecognitionBackend : Closeable {
    fun transcribeWindow(samples: ShortArray): WindowResult
}
