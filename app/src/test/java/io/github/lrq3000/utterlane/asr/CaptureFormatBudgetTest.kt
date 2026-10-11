package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.archive.PcmFormat
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import org.junit.Assert.*
import org.junit.Test

class CaptureFormatBudgetTest {
    @Test fun preparationAndReopenConstructTheSameUserConfiguredBuffer() {
        val options = RuntimeOptions(captureBufferSeconds = .25, captureBlockMs = 50, queueSeconds = 2)
        val format = PcmFormat(48000, 2)
        val configuration = CaptureConfiguration(format, options, 1920)
        val constructed = mutableListOf<Pair<PcmFormat, Int>>()
        val factory: (PcmFormat, Int) -> ByteArray = { actual, bytes -> constructed += actual to bytes; ByteArray(bytes) }
        val prepared = configuration.create(factory)
        val laterPreferences = options.copy(captureBufferSeconds = 3.0)
        val reopened = configuration.create(factory)
        assertEquals(48000, prepared.size)
        assertEquals(prepared.size, reopened.size)
        assertEquals(listOf(format to 48000, format to 48000), constructed)
        assertEquals(.25, configuration.options.captureBufferSeconds, 0.0)
        assertNotEquals(laterPreferences, configuration.options)
    }
    @Test fun realDeviceMinimumWinsWithoutChangingTheSnapshotTimeBudget() {
        val configuration = CaptureConfiguration(PcmFormat(44100, 2), RuntimeOptions(captureBufferSeconds = .25), 70000)
        assertEquals(70000, configuration.create { _, bytes -> bytes })
    }
    @Test fun stereoBudgetMeasuresRealCaptureSecondsAndStillRejectsOverflow() {
        val queue = BoundedAudioQueue(RuntimeOptions(queueSeconds = 1), PcmFormat(48000, 2))
        repeat(30) { assertTrue(queue.offer(ShortArray(3200) { it.toShort() })) }
        assertFalse(queue.offer(shortArrayOf(1, 2)))
        queue.close()
    }
}
