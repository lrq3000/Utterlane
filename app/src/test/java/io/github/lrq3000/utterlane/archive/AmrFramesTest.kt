package io.github.lrq3000.utterlane.archive

import org.junit.Assert.*
import org.junit.Test

class AmrFramesTest {
    private fun frame(type: Int, length: Int) = ByteArray(length) { if (it == 0) ((type shl 3) or 4).toByte() else it.toByte() }
    @Test fun nbSplitsFourHundredMillisecondsIntoTwentyIndexedFrames() {
        val packet = frame(7, 32)
        val bundled = ByteArray(20 * packet.size) { packet[it % packet.size] }
        var frames = 0; var count = 0
        AmrFrames.split(bundled, 8000) { bytes, _ -> assertArrayEquals(packet, bytes); frames += 160; count++ }
        assertEquals(20, count); assertEquals(3200, frames)
    }
    @Test fun wbSplitsTwoHundredMillisecondsIntoTenIndexedFrames() {
        val packet = frame(8, 61)
        val bundled = ByteArray(10 * packet.size) { packet[it % packet.size] }
        val offsets = mutableListOf<Long>()
        AmrFrames.split(bundled, 16000, 6400) { bytes, start -> assertArrayEquals(packet, bytes); offsets += start }
        assertEquals((0 until 10).map { 6400L + it * 320 }, offsets)
    }
    @Test fun sidAndNoDataFramesPreserveTheirTwentyMillisecondClock() {
        val nb = frame(0, 13) + frame(8, 6) + frame(15, 1)
        val sizes = mutableListOf<Int>()
        AmrFrames.split(nb, 8000) { packet, _ -> sizes += packet.size }
        assertEquals(listOf(13, 6, 1), sizes)
        val wb = frame(0, 18) + frame(9, 6) + frame(14, 1) + frame(15, 1)
        var count = 0
        AmrFrames.split(wb, 16000) { _, _ -> count++ }
        assertEquals(4, count)
    }
    @Test fun truncatedOrReservedFramesCannotAdvanceCoverage() {
        assertThrows(IllegalArgumentException::class.java) { AmrFrames.split(frame(7, 31), 8000) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { AmrFrames.split(frame(10, 1), 16000) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { AmrFrames.split(byteArrayOf(0x84.toByte()), 8000) { _, _ -> } }
    }
}
