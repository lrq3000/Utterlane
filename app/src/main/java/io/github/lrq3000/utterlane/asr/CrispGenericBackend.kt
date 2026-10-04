package io.github.lrq3000.utterlane.asr

/** Generic speech dispatcher; text-only backends are given disjoint audio spans. */
class CrispGenericBackend(path: String, codecPath: String? = null) : RecognitionBackend {
    companion object { init { System.loadLibrary("utterlane_crisp") } }
    private var handle = openNative(path, codecPath).also { check(it != 0L) }
    @Synchronized override fun transcribeWindow(samples: ShortArray): WindowResult {
        check(handle != 0L)
        return WindowResult(emptyArray(), floatArrayOf(), decodeNative(handle, FloatArray(samples.size) { samples[it] / 32768f }))
    }
    @Synchronized override fun close() { if (handle != 0L) { closeNative(handle); handle = 0 } }
    private external fun openNative(path: String, codecPath: String?): Long
    private external fun decodeNative(handle: Long, samples: FloatArray): String
    private external fun closeNative(handle: Long)
}
