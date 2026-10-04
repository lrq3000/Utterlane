package io.github.lrq3000.utterlane.asr

class CrispSpeakerStream(path: String) : SpeakerProbabilityStream {
    companion object { init { System.loadLibrary("utterlane_crisp") } }
    private var handle = openNative(path).also { check(it != 0L) }
    override fun push(samples: ShortArray, final: Boolean): FloatArray {
        check(handle != 0L)
        return pushNative(handle, FloatArray(samples.size) { samples[it] / 32768f }, final)
    }
    override fun close() { if (handle != 0L) { closeNative(handle); handle = 0 } }
    private external fun openNative(path: String): Long
    private external fun pushNative(handle: Long, samples: FloatArray, final: Boolean): FloatArray
    private external fun closeNative(handle: Long)
}
