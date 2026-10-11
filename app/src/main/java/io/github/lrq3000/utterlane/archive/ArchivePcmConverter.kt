package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.transcribe.StreamingResampler

/** Per-channel resamplers share one frame clock and preserve genuine stereo. */
class ArchivePcmConverter(private val source: PcmFormat, private val target: PcmFormat) {
    private val resamplers = Array(target.channels) { StreamingResampler(source.sampleRate, 1, target.sampleRate) }
    private val partial = FloatArray(source.channels)
    private var position = 0
    init { require(source.channels in 1..32 && target.channels in 1..2) }
    fun accept(input: FloatArray): ShortArray {
        val count = (input.size + position) / source.channels
        val channels = Array(target.channels) { FloatArray(count) }
        var frame = 0
        for (value in input) {
            partial[position++] = value
            if (position != source.channels) continue
            if (target.channels == 1) channels[0][frame] = partial.average().toFloat()
            else if (source.channels == 1) { channels[0][frame] = partial[0]; channels[1][frame] = partial[0] }
            else { channels[0][frame] = partial[0]; channels[1][frame] = partial[1] }
            position = 0; frame++
        }
        return interleave(Array(target.channels) { resamplers[it].accept(channels[it]) })
    }
    fun finish(): ShortArray { check(position == 0) { "Incomplete source channel frame" }; return interleave(Array(target.channels) { resamplers[it].finish() }) }
    private fun interleave(channels: Array<ShortArray>): ShortArray {
        val size = channels[0].size
        check(channels.all { it.size == size })
        return ShortArray(size * target.channels) { channels[it % target.channels][it / target.channels] }
    }
}
