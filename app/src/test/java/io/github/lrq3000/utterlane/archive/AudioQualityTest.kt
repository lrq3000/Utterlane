package io.github.lrq3000.utterlane.archive

import org.junit.Assert.*
import org.junit.Test

class AudioQualityTest {
    @Test fun nativeDefaultAndAacCeiling() {
        val defaults = AudioQuality()
        assertFalse(defaults.compress)
        assertEquals(PcmFormat(16000, 1), defaults.capture)
        assertEquals(96000, defaults.bitRate)
        assertTrue(defaults.copy(compress = true).validTuple())
        assertFalse(defaults.copy(compress = true, bitRate = 192000).validTuple())
        assertTrue(defaults.copy(compress = true, channels = 2, bitRate = 192000).validTuple())
    }
    @Test fun amrUsesItsActualCodecRate() {
        val amr = AudioQuality(true, SavedAudioFormat.THREE_GP)
        assertEquals(23850, amr.effectiveBitRate)
        assertEquals(12200, amr.copy(sampleRate = 8000).effectiveBitRate)
        assertFalse(amr.copy(channels = 2).validTuple())
        assertFalse(amr.copy(sampleRate = 44100).validTuple())
    }
    @Test fun metadataRatherThanExtensionSelectsImportConversion() {
        val quality = AudioQuality(compress = true)
        assertFalse(quality.shouldEncodeImport(isCompressed = true, retained = true))
        assertFalse(quality.shouldEncodeImport(isCompressed = null, retained = true))
        assertFalse(quality.shouldEncodeImport(isCompressed = false, retained = false))
        assertTrue(quality.shouldEncodeImport(isCompressed = false, retained = true))
    }
}
