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
    private var strongCandidateFrames = 0
    // Session history must survive ring pruning, also in Auto where no fixed
    // identity slots are allocated. Saturated counters keep this constant-space.
    private var firstConfirmed: SpeakerTurn? = null
    private val initialActiveFrames = IntArray(8)
    private var first = 0L
    private var end = 0L
    val endSample: Long get() = end * 160
    val retainedFrames: Int get() = (end - first).toInt()

    fun append(probabilities: FloatArray) {
        require(probabilities.size % 8 == 0 && probabilities.all { it.isFinite() && it in 0f..1f })
        for (offset in probabilities.indices step 8) {
            // A delayed native batch can exceed the configured history budget.
            // Old queries then become Unknown; storage never grows or corrupts.
            first = maxOf(first, end - capacity + 1)
            val slot = (end % capacity).toInt() * 8
            probabilities.copyInto(this.probabilities, slot, offset, offset + 8)
            if (firstConfirmed == null) for (channel in 0..7) {
                if (probabilities[offset + channel] > options.speakerThreshold) {
                    initialActiveFrames[channel] = minOf(3, initialActiveFrames[channel] + 1)
                }
            }
            val best = channelAt(end)
            val confirmation = maxOf(3, (options.speakerConfirmationMs + 9) / 10)
            val sameCandidate = best == candidate
            candidateFrames = if (sameCandidate) minOf(candidateFrames + 1, confirmation) else 1
            // Three strong frames can establish a genuinely brief interjection;
            // a single-frame spike never consumes a scarce fixed-count identity.
            val strong = best >= 0 && channelAt(end, maxOf(.7f, options.speakerThreshold),
                maxOf(.2f, options.speakerMargin)) == best
            // Weak support counts toward full confirmation only. One later
            // strong frame must not retroactively promote that earlier support.
            strongCandidateFrames = if (strong) {
                if (sameCandidate) minOf(strongCandidateFrames + 1, 3) else 1
            } else 0
            candidate = best
            if (best >= 0 && (strongCandidateFrames >= 3 || candidateFrames >= confirmation)) {
                if (firstConfirmed == null) {
                    val confirmedFrames = if (strongCandidateFrames >= 3) 3 else confirmation
                    firstConfirmed = SpeakerTurn((end - confirmedFrames + 1) * 160, (end + 1) * 160, best)
                }
                if (identities[best] < 0 && identityCount < speakerCount) identities[best] = identityCount++
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
            first = maxOf(first, end - capacity + 1)
            last.copyInto(probabilities, (end % capacity).toInt() * 8)
            end++
        }
    }

    fun speakerAt(sample: Long): Int {
        val frame = sample / 160
        if (sample < 0 || frame < first || frame >= end) return -1
        return identity(channelAt(frame))
    }

    /**
     * Integrate voiced evidence over a lexical interval, never just its onset.
     * Callers can disable future-dependent fallbacks for early commitment.
     */
    fun speakerDuring(start: Long, stop: Long, previous: Int = -1, coarse: Boolean = false,
                      allowFallback: Boolean = true): Int {
        require(start >= 0 && stop >= start)
        if (start < first * 160 || stop > endSample) return -1
        val evidence = evidence(start, stop)
        if (coarse && evidence.support.count { it >= 3 * 160 } > 1) return -1
        val winner = evidence.winner(previous)
        if (winner >= 0) return identity(winner)
        if (!allowFallback) return -1

        val tolerance = options.alignmentToleranceMs * 16L
        // ASR endpoints can straddle an unvoiced onset/offset. Inspect only
        // their configured uncertainty, not the whole gap to another turn.
        // A long silent interior outside both endpoint tolerances still obeys
        // the unknown-gap bound; untimed whole-window text has no such timing.
        if (!coarse && tolerance > 0 && stop - start <= options.unknownBridgeMs * 16L + 2 * tolerance) {
            val expanded = this.evidence(maxOf(first * 160, start - tolerance), minOf(endSample, stop + tolerance))
            val aligned = expanded.unambiguousWinner(previous)
            if (aligned >= 0) return identity(aligned)
        }
        // An actual conflict is not silence. Never bridge overlapping voices or
        // sustained competing evidence merely because nearby labels agree.
        if (evidence.voiced >= 3 * 160) return -1
        val initial = firstConfirmed
        // Initial ASR words can end just before the first voiced onset after a
        // long leading silence. Backfill only toward that session's first
        // confirmed voice, within the configured end-to-onset distance, and
        // never through an initially competing track (even if it was pruned).
        if (!coarse && initial != null && options.unknownBridgeMs > 0 && stop <= initial.start &&
            initial.start - stop <= options.unknownBridgeMs * 16L &&
            initialActiveFrames.indices.none { it != initial.speaker && initialActiveFrames[it] >= 3 }) {
            return identity(initial.speaker)
        }
        val search = maxOf(options.unknownBridgeMs, options.alignmentToleranceMs) * 16L
        val left = neighbor(start / 160 - 1, -1, search)
        val right = neighbor((stop + 159) / 160, 1, search)
        if (left != null && right != null && left.first == right.first &&
            (right.second - left.second - 1) * 160 <= options.unknownBridgeMs * 16L) {
            return identity(left.first)
        }
        // A clipped first/last word has no two-sided neighbor. Permit only the
        // explicit timestamp tolerance at the retained audio edges, not a long
        // unbounded propagation of the last known identity into silence.
        if (start < first * 160 + tolerance && left == null && right != null &&
            right.second * 160 - start <= tolerance) return identity(right.first)
        if (stop > endSample - tolerance && right == null && left != null &&
            stop - (left.second + 1) * 160 <= tolerance) return identity(left.first)
        return -1
    }

    private inner class Evidence {
        val mass = DoubleArray(8)
        val support = LongArray(8)
        val active = LongArray(8)
        var voiced = 0L
        fun winner(previous: Int): Int {
            if (voiced == 0L) return -1
            var best = 0
            for (i in 1..7) if (mass[i] > mass[best]) best = i
            var second = 0.0
            for (i in 0..7) if (i != best) second = maxOf(second, mass[i])
            val confidence = mass[best] / voiced
            val margin = (mass[best] - second) / voiced
            if (confidence <= options.speakerThreshold || margin < options.speakerMargin || margin <= 0) return -1
            val strong = confidence >= maxOf(.7, options.speakerThreshold.toDouble()) &&
                margin >= maxOf(.2, options.speakerMargin.toDouble())
            val required = if (strong || identity(best) == previous && previous >= 0) 3 * 160L
                else maxOf(3 * 160L, options.speakerConfirmationMs * 16L)
            return if (voiced >= required) best else -1
        }

        fun unambiguousWinner(previous: Int): Int {
            val winner = winner(previous)
            // Count every credible channel, including tied/overlapping frames
            // with no decisive frame winner. Since this interval contains the
            // original word, conflicting word evidence cannot be outvoted by
            // extra surrounding audio. Isolated frame spikes remain insufficient.
            return if (winner >= 0 && active[winner] >= 3 * 160 &&
                active.indices.none { it != winner && active[it] >= 3 * 160 }) winner else -1
        }
    }

    private fun evidence(start: Long, stop: Long): Evidence {
        val result = Evidence()
        for (frame in maxOf(first, start / 160) until minOf(end, (stop + 159) / 160)) {
            val offset = (frame % capacity).toInt() * 8
            var peak = 0f
            for (i in 0..7) peak = maxOf(peak, probabilities[offset + i])
            if (peak <= options.speakerThreshold) continue
            val weight = minOf(stop, (frame + 1) * 160) - maxOf(start, frame * 160)
            result.voiced += weight
            val channel = channelAt(frame)
            if (channel >= 0) result.support[channel] += weight
            for (i in 0..7) {
                result.mass[i] += probabilities[offset + i] * weight.toDouble()
                if (probabilities[offset + i] > options.speakerThreshold) result.active[i] += weight
            }
        }
        return result
    }

    /** Nearest sustained evidence; isolated 10 ms spikes cannot anchor a bridge. */
    private fun neighbor(from: Long, direction: Int, distance: Long): Pair<Int, Long>? {
        var frame = from
        var run = 0
        var channel = -1
        while (frame in first until end && kotlin.math.abs(frame - from) * 160 <= distance) {
            val next = channelAt(frame)
            run = if (next >= 0 && next == channel) run + 1 else if (next >= 0) 1 else 0
            channel = next
            if (run >= 3) return channel to (frame - direction * 2)
            frame += direction
        }
        return null
    }

    private fun identity(channel: Int): Int = when {
        channel < 0 -> -1
        speakerCount == 0 -> channel
        else -> identities[channel]
    }

    private fun channelAt(frame: Long, threshold: Float = options.speakerThreshold, margin: Float = options.speakerMargin): Int {
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
        return if (confidence > threshold && confidence - runnerUp >= margin &&
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
