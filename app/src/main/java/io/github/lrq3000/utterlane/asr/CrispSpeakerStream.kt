package io.github.lrq3000.utterlane.asr

import androidx.annotation.Keep
import io.github.lrq3000.utterlane.settings.RuntimeOptions

class CrispSpeakerStream(path: String, options: RuntimeOptions = RuntimeOptions(),
    private val onProgress: (Long, String) -> Unit = { _, _ -> }) : SpeakerProbabilityStream {
    companion object { init { System.loadLibrary("utterlane_crisp") } }
    private val configured = options.requireValid()
    private var handle = openNative(path, RuntimeOptions.resolveThreads(configured.diarizationThreads),
        configured.diarizationMode, configured.diarizationBatch, configured.nativeCacheFrames,
        configured.nativeFifoFrames, configured.nativeUpdateFrames).also { check(it != 0L) }
    @Keep private fun onNativeProgress(units: Long, stage: Int) {
        onProgress(units, when (stage) { 0 -> "features"; 1 -> "transformer"; else -> "cache" })
    }
    override fun push(samples: ShortArray, final: Boolean): FloatArray {
        check(handle != 0L)
        return pushNative(handle, FloatArray(samples.size) { samples[it] / 32768f }, final)
    }
    override fun close() { if (handle != 0L) { closeNative(handle); handle = 0 } }
    private external fun openNative(path: String, threads: Int, mode: String, batch: Int, cache: Int, fifo: Int, update: Int): Long
    private external fun pushNative(handle: Long, samples: FloatArray, final: Boolean): FloatArray
    private external fun closeNative(handle: Long)
}
