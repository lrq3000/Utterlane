package io.github.lrq3000.utterlane.archive

import kotlin.math.PI
import kotlin.math.sqrt

/** Bounded, non-periodic band-limited probe. Both separated analysis windows must
 * agree on a unique sample offset; silence, periodic aliases and drift fail closed. */
object PcmPrimingEstimator {
    const val PROBE_FRAMES = 16384
    const val MAX_DELAY = 8192
    data class Result(val frames: Int, val correlation: Double)
    fun probe(sampleRate: Int): FloatArray {
        var random = 0x4a38172b
        var low = 0.0; var dc = 0.0
        val alpha = (2 * PI * 1200 / sampleRate).coerceAtMost(.65)
        return FloatArray(PROBE_FRAMES) {
            random = random xor (random shl 13); random = random xor (random ushr 17); random = random xor (random shl 5)
            low += alpha * (random.toDouble() / Int.MAX_VALUE - low)
            dc += .001 * (low - dc)
            // Quantize exactly as the codec input, not as an idealized float probe.
            ((low - dc) * 22000).toInt().coerceIn(-32768, 32767) / 32768f
        }
    }
    fun measure(reference: FloatArray, decoded: FloatArray, checkActive: () -> Unit = {}): Result {
        require(reference.size == PROBE_FRAMES && decoded.size <= PROBE_FRAMES + MAX_DELAY + 8192)
        val windows = listOf(2048 until 6144, 10240 until 14336)
        val limit = minOf(MAX_DELAY, decoded.size - 14336)
        check(limit >= 0) { "AAC calibration output is incomplete" }
        fun correlation(lag: Int, stride: Int, window: IntRange? = null): Double {
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (range in if (window == null) windows else listOf(window)) {
                var i = range.first
                while (i <= range.last) {
                    val x = reference[i].toDouble(); val y = decoded[i + lag].toDouble()
                    xy += x * y; xx += x * x; yy += y * y; i += stride
                }
            }
            if (xx < 1e-9 || yy < xx * .01 || yy > xx * 100) return -1.0
            return xy / sqrt(xx * yy)
        }
        var best = 0; var score = -1.0
        for (lag in 0..limit step 2) {
            if (lag % 128 == 0) checkActive()
            val value = correlation(lag, 8)
            if (value > score) { score = value; best = lag }
        }
        val coarse = best; score = -1.0
        for (lag in maxOf(0, coarse - 3)..minOf(limit, coarse + 3)) {
            val value = correlation(lag, 1)
            if (value > score) { score = value; best = lag }
        }
        check(score >= .65) { "AAC calibration could not establish a reliable priming offset" }
        // Reject ambiguous periodic matches outside the local correlation peak.
        for (lag in 0..limit step 8) if (kotlin.math.abs(lag - best) >= 128) {
            check(correlation(lag, 8) < score - .1) { "AAC calibration has ambiguous timing" }
        }
        for (window in windows) {
            var localBest = best; var localScore = -1.0
            for (lag in maxOf(0, best - 3)..minOf(limit, best + 3)) {
                val value = correlation(lag, 1, window)
                if (value > localScore) { localScore = value; localBest = lag }
            }
            check(localBest == best && localScore >= .65) { "AAC calibration timing is not stable across the probe" }
        }
        check(decoded.size - best >= reference.size) { "AAC calibration lost the final input tail" }
        // Interior alignment alone cannot prove that EOS retained both edges.
        // Extra zero output is not evidence: require the actual source signal.
        for (window in listOf(0 until 1024, reference.size - 1024 until reference.size)) {
            val edgeScore = correlation(best, 1, window)
            check(edgeScore >= .65) { "AAC calibration lost source signal at ${window.first}: correlation=$edgeScore" }
        }
        return Result(best, score)
    }
}
