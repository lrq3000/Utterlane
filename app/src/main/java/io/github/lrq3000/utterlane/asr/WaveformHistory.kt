package io.github.lrq3000.utterlane.asr

import kotlin.math.log10
import kotlin.math.sqrt

/** Fixed 6.4-second history: changing refresh rate never changes the audio time axis. */
internal class WaveformHistory {
    private val levels = FloatArray(64)
    private var cursor = 0
    private var bucketSamples = 0
    private var energy = 0.0
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
            energy += square
            if (++bucketSamples == 1600) {
                levels[cursor] = normalized(decibels(energy, bucketSamples))
                cursor = (cursor + 1) % levels.size
                energy = 0.0
                bucketSamples = 0
            }
        }
        val db = decibels(blockEnergy, pcm.size)
        level = normalized(db)
        audible = db > -55
        if (pcm.isNotEmpty()) revision++
    }

    // Historical buckets remain fixed in audio time. The last point can show
    // the current partial bucket, so a 60/200 Hz display is not secretly capped
    // by the 100 ms history buckets. Energy still accumulates only once per PCM
    // sample; a safe snapshot is built only when a visual publication is due.
    fun snapshot(): FloatArray {
        if (revision != publishedRevision) {
            val partial = bucketSamples > 0
            val tip = if (partial) normalized(decibels(energy, bucketSamples)) else 0f
            published = FloatArray(levels.size) {
                if (partial && it == levels.lastIndex) tip
                else levels[(cursor + (if (partial) 1 else 0) + it) % levels.size]
            }
            publishedRevision = revision
        }
        return published
    }

    private fun decibels(squares: Double, count: Int): Double =
        if (count == 0 || squares == 0.0) -140.0 else 20 * log10(sqrt(squares / count) / 32768.0)
    private fun normalized(db: Double) = ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
}
