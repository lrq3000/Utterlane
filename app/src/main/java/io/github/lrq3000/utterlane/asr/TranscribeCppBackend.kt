package io.github.lrq3000.utterlane.asr

/** Compact native-ternary Parakeet; its patched ggml is private to this JNI library. */
class TranscribeCppBackend(path: String, threads: Int = 4) : RecognitionBackend {
    companion object { init { System.loadLibrary("utterlane_transcribe") } }
    private var handle = openNative(path, io.github.lrq3000.utterlane.settings.RuntimeOptions.resolveThreads(threads))
    init { check(handle != 0L) { "Native ternary model initialization failed" } }

    data class WeightLayout(val ternaryTensors: Long, val ternaryBytes: Long, val tensorBytes: Long)

    @Synchronized fun weightLayout(): WeightLayout {
        check(handle != 0L) { "Model was unloaded" }
        val values = layoutNative(handle)
        return WeightLayout(values[0], values[1], values[2])
    }

    @Synchronized override fun transcribeWindow(samples: ShortArray): WindowResult {
        check(handle != 0L) { "Model was unloaded" }
        require(samples.size in 1..192000)
        val values = decodeNative(handle, FloatArray(samples.size) { samples[it] / 32768f })
        @Suppress("UNCHECKED_CAST")
        return WindowResult(values[0] as Array<String>, values[1] as FloatArray, ends = values[2] as FloatArray)
    }
    @Synchronized override fun close() { if (handle != 0L) { closeNative(handle); handle = 0 } }
    private external fun openNative(path: String, threads: Int): Long
    private external fun decodeNative(handle: Long, samples: FloatArray): Array<Any>
    private external fun layoutNative(handle: Long): LongArray
    private external fun closeNative(handle: Long)
}
