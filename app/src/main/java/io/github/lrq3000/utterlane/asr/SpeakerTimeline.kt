package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions

data class SpeakerTurn(val start: Long, val end: Long, val speaker: Int)
data class SpeechSpan(val text: String, val speaker: Int)

/** Absolute 10 ms coordinates, bounded storage, and session-stable native track identities. */
class SpeakerTimeline(
    private val speakerCount: Int,
    private val capacity: Int = 1600,
    private val options: RuntimeOptions = RuntimeOptions()
) {
    init { require(speakerCount in 0..8 && capacity in 1..20000); options.requireValid() }
    // Keep every posterior, including channels beyond an explicit count. A count
    // constrains output identities, not the native model's eight-channel evidence.
    private val probabilities = FloatArray(capacity * 8)
    private val identities = IntArray(8) { -1 }
    private var identityCount = 0
    private var candidate = -1
    private var candidateFrames = 0
    private var first = 0L
    private var end = 0L
    val endSample: Long get() = end * 160
    val retainedFrames: Int get() = (end - first).toInt()

    fun append(probabilities: FloatArray) {
        require(probabilities.size % 8 == 0 && probabilities.all { it.isFinite() && it in 0f..1f })
        check(retainedFrames + probabilities.size / 8 <= capacity) { "Speaker timeline exceeded its bounded audio window" }
        for (offset in probabilities.indices step 8) {
            val slot = (end % capacity).toInt() * 8
            probabilities.copyInto(this.probabilities, slot, offset, offset + 8)
            val best = channelAt(end)
            candidateFrames = if (best == candidate) candidateFrames + 1 else 1
            candidate = best
            // Three strong frames can establish a genuinely brief interjection;
            // a single-frame spike never consumes a scarce fixed-count identity.
            if (best >= 0 && candidateFrames >= 3 && identities[best] < 0 &&
                identityCount < speakerCount) {
                identities[best] = identityCount++
            }
            end++
        }
    }

    fun discardBefore(sample: Long) { first = maxOf(first, minOf(sample / 160, end)) }

    fun finishAt(sample: Long) {
        // The centered 512-sample FFT cannot score its trailing half-window.
        // Extend only this <=16 ms tail, not an arbitrary inference shortfall.
        check(sample - endSample <= 256) { "Diarization ended before the final FFT window" }
        val last = if (end > first) probabilities.copyOfRange(((end - 1) % capacity).toInt() * 8,
            ((end - 1) % capacity).toInt() * 8 + 8) else FloatArray(8)
        while (endSample < sample) {
            check(retainedFrames < capacity)
            last.copyInto(probabilities, (end % capacity).toInt() * 8)
            end++
        }
    }

    fun speakerAt(sample: Long): Int {
        val frame = sample / 160
        if (sample < 0 || frame < first || frame >= end) return -1
        return identity(channelAt(frame))
    }

    private fun identity(channel: Int): Int = when {
        channel < 0 -> -1
        speakerCount == 0 -> channel
        else -> identities[channel]
    }

    private fun channelAt(frame: Long): Int {
        val offset = (frame % capacity).toInt() * 8
        var best = 0
        var runnerUp = 0f
        for (channel in 1..7) {
            if (probabilities[offset + channel] > probabilities[offset + best]) {
                runnerUp = probabilities[offset + best]
                best = channel
            } else runnerUp = maxOf(runnerUp, probabilities[offset + channel])
        }
        val confidence = probabilities[offset + best]
        return if (confidence > options.speakerThreshold && confidence - runnerUp >= options.speakerMargin &&
            confidence > runnerUp) best else -1
    }

    /** Partition ownership exactly, including unknown speech. No audio is dropped. */
    fun turns(start: Long, stop: Long): List<SpeakerTurn> {
        require(start >= 0 && stop >= start)
        val result = mutableListOf<SpeakerTurn>()
        var at = start
        while (at < stop) {
            val speaker = speakerAt(at)
            var next = minOf((at / 160 + 1) * 160, stop)
            while (next < stop && speakerAt(next) == speaker) next = minOf(next + 160, stop)
            if (result.lastOrNull()?.speaker == speaker) {
                val previous = result.removeAt(result.lastIndex)
                result += previous.copy(end = next)
            } else result += SpeakerTurn(at, next, speaker)
            at = next
        }
        return result
    }
}
