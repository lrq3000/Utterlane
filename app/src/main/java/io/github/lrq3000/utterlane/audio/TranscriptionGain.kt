package io.github.lrq3000.utterlane.audio

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Owns bounded derived-audio processing; it never receives the authoritative writer buffer. */
class TranscriptionGain(private val mode: PcmGainMode, private val sampleRate: Int = 16000) {
    private val window = ShortArray((sampleRate / 50).coerceAtLeast(1)) // Fixed 20 ms analysis windows.
    private var pending = 0
    private var gain = 1.0
    private var finished = false
    private val fixedGain = when (mode) {
        PcmGainMode.DB_PLUS_6 -> 10.0.pow(6.0 / 20)
        PcmGainMode.DB_PLUS_12 -> 10.0.pow(12.0 / 20)
        PcmGainMode.DB_PLUS_18 -> 10.0.pow(18.0 / 20)
        else -> 1.0
    }
    init { require(sampleRate > 0) }

    /** The caller transfers ownership of this derived reader buffer, not the saved capture. */
    fun accept(samples: ShortArray): ShortArray {
        check(!finished)
        if (mode == PcmGainMode.OFF) return samples
        if (mode != PcmGainMode.AUTO_LEVEL) {
            for (i in samples.indices) samples[i] = scaled(samples[i], fixedGain)
            return samples
        }
        val output = ShortArray((pending + samples.size) / window.size * window.size)
        var inputOffset = 0
        var outputOffset = 0
        while (inputOffset < samples.size) {
            val count = minOf(window.size - pending, samples.size - inputOffset)
            samples.copyInto(window, pending, inputOffset, inputOffset + count)
            pending += count; inputOffset += count
            if (pending == window.size) {
                render(output, outputOffset, pending)
                outputOffset += pending; pending = 0
            }
        }
        return output
    }

    fun finish(): ShortArray {
        if (finished) return shortArrayOf()
        finished = true
        return ShortArray(pending).also { if (pending > 0) render(it, 0, pending); pending = 0 }
    }

    suspend fun deliver(samples: ShortArray, sink: suspend (ShortArray) -> Unit) {
        val processed = accept(samples)
        if (processed.isNotEmpty()) sink(processed)
    }

    suspend fun drain(sink: suspend (ShortArray) -> Unit) {
        val tail = finish()
        if (tail.isNotEmpty()) sink(tail)
    }

    private fun render(output: ShortArray, offset: Int, count: Int) {
        var squares = 0.0
        for (i in 0 until count) { val sample = window[i].toDouble(); squares += sample * sample }
        val rms = sqrt(squares / count)
        val desired = if (rms < SILENCE_RMS) 1.0 else (TARGET_RMS / rms).coerceAtMost(MAX_GAIN)
        val seconds = count.toDouble() / sampleRate
        val end = desired + (gain - desired) * exp(-seconds / if (desired < gain) 0.050 else 0.500)
        for (i in 0 until count) {
            val fraction = if (count == 1) 1.0 else i.toDouble() / (count - 1)
            output[offset + i] = scaled(window[i], gain + (end - gain) * fraction)
        }
        gain = end
    }

    private fun scaled(sample: Short, multiplier: Double) =
        (sample * multiplier).roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        private val TARGET_RMS = 32768.0 * 10.0.pow(-18.0 / 20)
        private val MAX_GAIN = 10.0.pow(18.0 / 20)
        private const val SILENCE_RMS = 32.768
    }
}
