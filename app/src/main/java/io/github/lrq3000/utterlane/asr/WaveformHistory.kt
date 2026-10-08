package io.github.lrq3000.utterlane.asr

import kotlin.math.log10
import kotlin.math.sqrt

/** Last 64 microphone callbacks, preserving the original waveform's motion and
 * amplitude detail. Capture advances history; refresh rate only gates snapshots. */
internal class WaveformHistory {
    private val levels = FloatArray(64)
    private var cursor = 0
    private var revision = 0L
    private var publishedRevision = -1L
    private var published = FloatArray(64)
    var level = 0f
        private set
    var audible = false
        private set

    fun accept(pcm: ShortArray) {
        var blockEnergy = 0.0
        for (sample in pcm) {
            val square = sample.toDouble() * sample
            blockEnergy += square
        }
        val db = decibels(blockEnergy, pcm.size)
        level = normalized(db)
        audible = db > -55
        if (pcm.isNotEmpty()) {
            // Do not aggregate short callbacks into slower audio-time buckets:
            // each incoming RMS value is a finished point, even between redraws.
            levels[cursor] = level
            cursor = (cursor + 1) % levels.size
            revision++
        }
    }

    // A low refresh rate may show several new points at once, never slow their
    // progression. Copy only at publication; older snapshots remain immutable,
    // and ticks without new audio reuse the array instead of redrawing the view.
    fun snapshot(): FloatArray {
        if (revision != publishedRevision) {
            published = FloatArray(levels.size) { levels[(cursor + it) % levels.size] }
            publishedRevision = revision
        }
        return published
    }

    private fun decibels(squares: Double, count: Int): Double =
        if (count == 0 || squares == 0.0) -140.0 else 20 * log10(sqrt(squares / count) / 32768.0)
    private fun normalized(db: Double) = ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
}
