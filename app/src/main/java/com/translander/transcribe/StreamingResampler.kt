package com.translander.transcribe

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Carries interpolation phase and partial channel frames across arbitrary codec buffers. */
class StreamingResampler(private val fromRate: Int, private val channels: Int, toRate: Int = 16000) {
    private val step = fromRate.toDouble() / toRate
    private var frames = 0L
    private var nextPosition = 0.0
    private var previous = 0f
    private var channelSum = 0.0
    private var channelIndex = 0

    init { require(fromRate > 0 && toRate > 0 && channels > 0) }

    fun accept(interleaved: FloatArray): ShortArray {
        val output = ShortArray(ceil((interleaved.size + channelIndex).toDouble() / channels / step).toInt() + 2)
        var count = 0
        for (sample in interleaved) {
            channelSum += if (sample.isFinite()) sample else 0f
            if (++channelIndex != channels) continue
            val current = (channelSum / channels).toFloat()
            channelSum = 0.0
            channelIndex = 0
            while (nextPosition <= frames.toDouble() + 1e-7) {
                val fraction = if (frames == 0L) 1.0 else nextPosition - (frames - 1)
                output[count++] = pcm(previous + (current - previous) * fraction)
                nextPosition += step
            }
            previous = current
            frames++
        }
        return output.copyOf(count)
    }

    fun finish(): ShortArray {
        val output = ShortArray(ceil(1.0 / step).toInt() + 1)
        var count = 0
        while (frames > 0 && nextPosition < frames - 1e-7) {
            output[count++] = pcm(previous.toDouble())
            nextPosition += step
        }
        return output.copyOf(count)
    }

    private fun pcm(value: Double): Short = (value * 32768).roundToInt().coerceIn(-32768, 32767).toShort()
}
