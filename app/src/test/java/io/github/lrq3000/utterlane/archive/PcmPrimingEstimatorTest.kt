package io.github.lrq3000.utterlane.archive

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class PcmPrimingEstimatorTest {
    @Test fun correlatedInteriorCannotCertifyTheMissingFinalAccessUnit() {
        val input = PcmPrimingEstimator.probe(16000)
        // Observed OMX boundary: 17 decoded LC packets, 2048 measured delay,
        // hence 1024 missing source frames despite excellent interior correlation.
        val decoded = FloatArray(17 * 1024)
        input.copyInto(decoded, 2048, 0, input.size - 1024)
        assertThrows(IllegalStateException::class.java) { PcmPrimingEstimator.measure(input, decoded) }
    }
    @Test fun fabricatedSilenceAtEitherSourceEdgeCannotCertifyAFullProbe() {
        val input = PcmPrimingEstimator.probe(16000)
        for (missingStart in listOf(true, false)) {
            val decoded = FloatArray(input.size + 4096)
            input.copyInto(decoded, 2048)
            val start = 2048 + if (missingStart) 0 else input.size - 1024
            decoded.fill(0f, start, start + 1024)
            assertThrows(IllegalStateException::class.java) { PcmPrimingEstimator.measure(input, decoded) }
        }
    }
    @Test fun oddSampleOffsetsAreMeasuredAtEveryCaptureRate() {
        for (rate in AudioQuality.SAMPLE_RATES) {
            val input = PcmPrimingEstimator.probe(rate)
            val decoded = FloatArray(input.size + 8192)
            input.copyInto(decoded, 2113)
            assertEquals("$rate Hz", 2113, PcmPrimingEstimator.measure(input, decoded).frames)
        }
    }
    @Test fun findsNonZeroDelayWithoutAnyCodecTimestampOrDelayKey() {
        val input = PcmPrimingEstimator.probe(16000)
        val decoded = FloatArray(input.size + 4096)
        input.indices.forEach { decoded[it + 2112] = input[it] * .87f + sin(it * 1.91).toFloat() * .002f }
        assertEquals(2112, PcmPrimingEstimator.measure(input, decoded).frames)
    }
    @Test fun trueZeroDelayNeedsSignalEvidenceRatherThanATimestampAssumption() {
        val input = PcmPrimingEstimator.probe(48000)
        assertEquals(0, PcmPrimingEstimator.measure(input, input.copyOf()).frames)
    }
    @Test fun silenceAndUnrelatedOutputAreRejected() {
        val input = PcmPrimingEstimator.probe(16000)
        assertThrows(IllegalStateException::class.java) { PcmPrimingEstimator.measure(input, FloatArray(input.size + 8192)) }
        val other = FloatArray(input.size + 8192) { sin(it * .79).toFloat() }
        assertThrows(IllegalStateException::class.java) { PcmPrimingEstimator.measure(input, other) }
    }
    @Test fun shiftedSecondHalfCannotCertifyAContinuousSourceClock() {
        val input = PcmPrimingEstimator.probe(16000)
        val decoded = FloatArray(input.size + 8192)
        input.indices.forEach { decoded[it + if (it < input.size / 2) 1024 else 1124] = input[it] }
        assertThrows(IllegalStateException::class.java) { PcmPrimingEstimator.measure(input, decoded) }
    }
}
