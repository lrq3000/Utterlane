package io.github.lrq3000.utterlane.archive

import org.junit.Assert.*
import org.junit.Test

class AacEndPaddingTest {
    @Test fun sourcePlusMeasuredDelayIsCoveredEvenWithoutExtraEncoderEosPackets() {
        for (source in listOf(1L, 317, 1023, 1024, 1025, 16384, 64317, 10_000_000_317)) {
            for (delay in listOf(0, 1024, 2048, 2113, PcmPrimingEstimator.MAX_DELAY)) {
                val padding = AacEndPadding.frames(source, delay)
                val completePackets = (source + padding) / 1024
                assertTrue("$source source frames / $delay priming frames", completePackets * 1024 - delay >= source)
                assertEquals(0L, (source + padding) % 1024)
                assertTrue(padding in delay..delay + 1023)
            }
        }
    }
    @Test fun rejectsUnmeasuredOrOutOfBoundsDelay() {
        assertThrows(IllegalArgumentException::class.java) { AacEndPadding.frames(16384, -1) }
        assertThrows(IllegalArgumentException::class.java) { AacEndPadding.frames(16384, PcmPrimingEstimator.MAX_DELAY + 1) }
    }
}
