package io.github.lrq3000.utterlane.asr

class CrispParakeetBackend(path: String) : RecognitionBackend {
    companion object { init { System.loadLibrary("utterlane_crisp") } }
    private var handle = openNative(path)
    init { check(handle != 0L) { "GGUF model initialization failed" } }

    @Synchronized override fun transcribeWindow(samples: ShortArray): WindowResult {
        check(handle != 0L) { "GGUF model was released" }
        require(samples.size in 1..192000)
        val result = decodeNative(handle, FloatArray(samples.size) { samples[it] / 32768f })
        @Suppress("UNCHECKED_CAST")
        return WindowResult(result[0] as Array<String>, result[1] as FloatArray)
    }
    @Synchronized override fun close() { if (handle != 0L) { closeNative(handle); handle = 0 } }
    private external fun openNative(path: String): Long
    private external fun decodeNative(handle: Long, samples: FloatArray): Array<Any>
    private external fun closeNative(handle: Long)
}
