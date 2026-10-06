package io.github.lrq3000.utterlane.asr

/** Generic speech dispatcher; text-only backends are given disjoint audio spans. */
class CrispGenericBackend(path: String, codecPath: String? = null, threads: Int = 4) : RecognitionBackend {
    companion object { init { System.loadLibrary("utterlane_crisp") } }
    private var progress: (Long, String) -> Unit = { _, _ -> }
    override fun setProgressListener(listener: (Long, String) -> Unit) { progress = listener }
    @androidx.annotation.Keep private fun onNativeProgress(samples: Long) { progress(samples, "encoded_audio") }
    private var handle = openNative(path, codecPath, io.github.lrq3000.utterlane.settings.RuntimeOptions.resolveThreads(threads)).also { check(it != 0L) }
    @Synchronized override fun transcribeWindow(samples: ShortArray): WindowResult {
        check(handle != 0L)
        val result = decodeNative(handle, FloatArray(samples.size) { samples[it] / 32768f })
        @Suppress("UNCHECKED_CAST")
        return WindowResult(result[0] as Array<String>, result[1] as FloatArray, result[3] as String, result[2] as FloatArray)
    }
    @Synchronized override fun close() { if (handle != 0L) { closeNative(handle); handle = 0 } }
    private external fun openNative(path: String, codecPath: String?, threads: Int): Long
    private external fun decodeNative(handle: Long, samples: FloatArray): Array<Any>
    private external fun closeNative(handle: Long)
}
