package io.github.lrq3000.utterlane.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TranscriptionGainTest {
    @Test fun fixedGainSaturatesAndOffPreservesSamples() {
        for ((mode, db) in listOf(PcmGainMode.DB_PLUS_6 to 6, PcmGainMode.DB_PLUS_12 to 12, PcmGainMode.DB_PLUS_18 to 18)) {
            val input = shortArrayOf(-32768, -1000, 0, 1000, 32767)
            val expected = input.map { (it * 10.0.pow(db / 20.0)).roundToInt().coerceIn(-32768, 32767).toShort() }.toShortArray()
            assertArrayEquals(expected, TranscriptionGain(mode).accept(input))
        }
        val input = shortArrayOf(-32768, -1, 0, 1, 32767)
        assertArrayEquals(input, TranscriptionGain(PcmGainMode.OFF).accept(input.copyOf()))
    }

    @Test fun arbitraryBlockSizesAndFinalTailProduceIdenticalAudio() {
        val audio = ShortArray(16_017) { ((if (it < 8000) 1000 else 9000) * sin(it * 0.37)).roundToInt().toShort() }
        assertArrayEquals(render(audio, listOf(audio.size)), render(audio, listOf(1, 19, 321, 799, 3200, 17)))
        assertEquals(audio.size, render(audio, listOf(113)).size)
    }

    @Test fun levelerReachesTargetAndPreservesNearSilenceWithoutGating() {
        val signal = ShortArray(96000) { (2000 * sin(it * 2 * PI * 1000 / 16000)).roundToInt().toShort() }
        val output = render(signal, listOf(800))
        val rms = sqrt(output.takeLast(16000).map { it.toDouble().pow(2) }.average())
        assertEquals(32768 * 10.0.pow(-18.0 / 20), rms, 4.0)
        assertArrayEquals(ShortArray(777), render(ShortArray(777), listOf(10)))
        val quiet = render(ShortArray(16000) { 10 }, listOf(113))
        assertTrue(quiet.all { it == 10.toShort() })
    }

    @Test fun levelerUsesSpecifiedAttackReleaseAndBoostLimit() {
        val target = 32768 * 10.0.pow(-18.0 / 20)
        val quiet = TranscriptionGain(PcmGainMode.AUTO_LEVEL).accept(ShortArray(320) { 1000 })
        assertEquals(1000, quiet.first().toInt())
        assertEquals((1000 * (target / 1000 + (1 - target / 1000) * exp(-0.020 / 0.500))).roundToInt(), quiet.last().toInt())
        val loud = TranscriptionGain(PcmGainMode.AUTO_LEVEL).accept(ShortArray(320) { 10000 })
        assertEquals((10000 * (target / 10000 + (1 - target / 10000) * exp(-0.020 / 0.050))).roundToInt(), loud.last().toInt())
        val gain = TranscriptionGain(PcmGainMode.AUTO_LEVEL)
        var result = shortArrayOf()
        repeat(200) { result = gain.accept(ShortArray(320) { 100 }) }
        assertEquals((100 * 10.0.pow(18.0 / 20)).roundToInt(), result.last().toInt())
        val transient = gain.accept(ShortArray(320) { if (it % 2 == 0) 32767 else -32768 })
        assertEquals(32767, transient[0].toInt())
        assertEquals(-32768, transient[1].toInt())
    }

    @Test fun gainRelaxesToUnityAndEmptyReadsDoNotAdvanceAudioTime() {
        val gain = TranscriptionGain(PcmGainMode.AUTO_LEVEL)
        repeat(100) { gain.accept(ShortArray(320) { 1000 }) }
        repeat(50) { assertTrue(gain.accept(shortArrayOf()).isEmpty()) }
        var quiet = shortArrayOf()
        repeat(250) { quiet = gain.accept(ShortArray(320) { 10 }) }
        assertTrue(quiet.all { it == 10.toShort() })
        val sixteen = TranscriptionGain(PcmGainMode.AUTO_LEVEL, 16000).accept(ShortArray(1600) { 1000 })
        val fortyEight = TranscriptionGain(PcmGainMode.AUTO_LEVEL, 48000).accept(ShortArray(4800) { 1000 })
        assertEquals(sixteen.last(), fortyEight.last())
    }

    private fun render(input: ShortArray, sizes: List<Int>): ShortArray {
        val gain = TranscriptionGain(PcmGainMode.AUTO_LEVEL)
        val result = ArrayList<Short>()
        var offset = 0
        var step = 0
        while (offset < input.size) {
            val end = minOf(input.size, offset + sizes[step++ % sizes.size])
            result.addAll(gain.accept(input.copyOfRange(offset, end)).toList())
            offset = end
        }
        result.addAll(gain.finish().toList())
        assertTrue(gain.finish().isEmpty())
        return result.toShortArray()
    }
}
