package io.github.lrq3000.utterlane.archive

import org.junit.Assert.*
import org.junit.Test

class ArchivePcmConverterTest {
    @Test fun sameFormatIsByteExactAndStereoIsNotFoldedIntoFakeMono() {
        val converter = ArchivePcmConverter(PcmFormat(44100, 2), PcmFormat(44100, 2))
        val raw = shortArrayOf(1, -7, 32767, -32768, 91, 1000)
        assertArrayEquals(raw, converter.accept(FloatArray(raw.size) { raw[it] / 32768f }))
        assertEquals(0, converter.finish().size)
    }
    @Test fun splitChannelFramesAndResamplingShareContinuousClock() {
        val raw = FloatArray(8820) { if (it % 2 == 0) .25f else -.5f }
        val whole = ArchivePcmConverter(PcmFormat(44100, 2), PcmFormat(16000, 2))
        val expected = whole.accept(raw) + whole.finish()
        val split = ArchivePcmConverter(PcmFormat(44100, 2), PcmFormat(16000, 2))
        var actual = ShortArray(0)
        for (chunk in raw.asList().chunked(317)) actual += split.accept(chunk.toFloatArray())
        actual += split.finish()
        assertArrayEquals(expected, actual)
        assertEquals(3200, actual.size)
        assertEquals(8192.toShort(), actual[0]); assertEquals((-16384).toShort(), actual[1])
    }
}
