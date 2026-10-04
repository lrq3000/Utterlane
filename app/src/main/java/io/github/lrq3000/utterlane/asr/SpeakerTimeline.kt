package io.github.lrq3000.utterlane.asr

data class SpeakerTurn(val start: Long, val end: Long, val speaker: Int)
data class SpeechSpan(val text: String, val speaker: Int)

/** Eight arrival-order tracks, not per-chunk cluster numbers. O(1) frame lookup. */
class SpeakerTimeline(speakerCount: Int, private val capacity: Int = 1600) {
    init { require(speakerCount in 0..8 && capacity > 0) }
    private val tracks = if (speakerCount == 0) 8 else speakerCount
    private val labels = IntArray(capacity)
    private var first = 0L
    private var end = 0L
    val endSample: Long get() = end * 160
    val retainedFrames: Int get() = (end - first).toInt()

    fun append(probabilities: FloatArray) {
        require(probabilities.size % 8 == 0 && probabilities.all { it.isFinite() && it in 0f..1f })
        check(retainedFrames + probabilities.size / 8 <= capacity) { "Speaker timeline exceeded its bounded audio window" }
        for (offset in probabilities.indices step 8) {
            var best = -1
            var confidence = 0.5f
            for (speaker in 0 until tracks) if (probabilities[offset + speaker] > confidence) {
                best = speaker; confidence = probabilities[offset + speaker]
            }
            labels[(end % capacity).toInt()] = best
            end++
        }
    }

    fun discardBefore(sample: Long) { first = maxOf(first, minOf(sample / 160, end)) }

    fun finishAt(sample: Long) {
        // The centered 512-sample FFT cannot score its trailing half-window.
        // Extend only this <=16 ms tail, not an arbitrary inference shortfall.
        check(sample - endSample <= 256) { "Diarization ended before the final FFT window" }
        val last = if (end > first) labels[((end - 1) % capacity).toInt()] else -1
        while (endSample < sample) {
            check(retainedFrames < capacity)
            labels[(end % capacity).toInt()] = last
            end++
        }
    }

    fun speakerAt(sample: Long): Int {
        val frame = sample / 160
        if (frame < first || frame >= end) return -1
        return labels[(frame % capacity).toInt()]
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
            // Do not turn frame-level flicker into dozens of tiny ASR calls.
            // A <200 ms run joins its predecessor; long turns remain unchanged.
            if (result.isNotEmpty() && next - at < 3200) {
                val previous = result.removeAt(result.lastIndex)
                result += previous.copy(end = next)
            } else if (result.lastOrNull()?.speaker == speaker) {
                val previous = result.removeAt(result.lastIndex)
                result += previous.copy(end = next)
            } else result += SpeakerTurn(at, next, speaker)
            at = next
        }
        return result
    }
}
