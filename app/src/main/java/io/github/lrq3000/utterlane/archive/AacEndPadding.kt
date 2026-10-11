package io.github.lrq3000.utterlane.archive

/** Silence fed only to the codec after source EOF. It advances the codec clock,
 * never capture/ASR/retention duration. Empty-buffer EOS alone need not flush a
 * full measured encode/decode delay (observed with Android OMX AAC-LC). */
internal object AacEndPadding {
    fun frames(sourceFrames: Long, measuredDelay: Int): Int {
        require(sourceFrames >= 0 && measuredDelay in 0..PcmPrimingEstimator.MAX_DELAY)
        // Align the *combined* input, including a short/partial source, to a full
        // LC access unit. Bounded by measuredDelay + 1023, independent of duration.
        val remainder = (sourceFrames % 1024 + measuredDelay) % 1024
        return measuredDelay + ((1024 - remainder) % 1024).toInt()
    }
}
