package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CaptureMetricsTest {
    @Test fun fileInputPublishesItsMeasuredRateBeforeEofWithoutCountingOverlapTwice() {
        var clock = 0L
        val meter = CaptureMetrics { clock }
        meter.captured(320000)
        assertNull(meter.state.value.processingSecondsPerSample)
        meter.processed(160000, 4000)
        clock = 1000; meter.tick()
        assertEquals(0.000025, meter.state.value.processingSecondsPerSample!!, 1e-10)
        meter.processed(160000, 10000) // Repeated/overlapping ownership adds no work.
        clock += 1000; meter.tick()
        assertEquals(0.000025, meter.state.value.processingSecondsPerSample!!, 1e-10)
        meter.processed(320000, 8000)
        clock += 1000; meter.tick()
        assertEquals(0.000030, meter.state.value.processingSecondsPerSample!!, 1e-10)
        assertNull("File input has not ended; microphone-tail percent is still inapplicable", meter.state.value.percent)
    }
    @Test fun inputWarningSurvivesSamplesRecognitionFailureAndFinalizationButIgnoresLateCallbacks() {
        val meter = CaptureMetrics { 0L }
        val warning = io.github.lrq3000.utterlane.audio.CaptureInputState(
            actual = io.github.lrq3000.utterlane.audio.AudioInput("phone", "", false),
            fallbackFrom = io.github.lrq3000.utterlane.audio.AudioInput("headset", "Headset", true),
            fallbackReason = io.github.lrq3000.utterlane.audio.InputFallbackReason.DISCONNECTED,
            receivingFallback = true)
        meter.started()
        meter.input(warning)
        meter.samples(ShortArray(800), true)
        meter.recognitionFailed("Model failed independently")
        meter.stopping(); meter.captureEnded(); meter.completed(null)
        assertEquals(warning, meter.state.value.input)
        meter.input(io.github.lrq3000.utterlane.audio.CaptureInputState())
        assertEquals(warning, meter.state.value.input)
    }
    @Test fun waveformReflectsActualPcmAndSilenceClearsOnSignal() {
        var clock = 0L
        val meter = CaptureMetrics { clock }
        meter.started()
        meter.samples(ShortArray(800), true)
        clock = 2000
        meter.samples(ShortArray(800), true) // Frames continue arriving, but they contain silence.
        meter.tick()
        assertEquals(CaptureSignal.LOW, meter.state.value.signal)
        assertEquals(0f, meter.state.value.level, 0f)
        meter.samples(ShortArray(800) { 8000 }, true)
        assertEquals(CaptureSignal.AUDIO, meter.state.value.signal)
        assertTrue(meter.state.value.level > 0.5f)
        assertEquals(64, meter.state.value.waveform.size)
        repeat(1000) { meter.samples(ShortArray(800) { 1000 }, true) }
        assertEquals(64, meter.state.value.waveform.size)
    }
    @Test fun noFramesAndSystemSilencingHaveDistinctStates() {
        var clock = 0L
        val meter = CaptureMetrics { clock }
        meter.started(); clock = 2000; meter.tick()
        assertEquals(CaptureSignal.NO_FRAMES, meter.state.value.signal)
        meter.silenced(true)
        assertEquals(CaptureSignal.BLOCKED, meter.state.value.signal)
        meter.silenced(false); meter.samples(ShortArray(800) { 8000 }, true)
        assertEquals(CaptureSignal.AUDIO, meter.state.value.signal)
    }
    @Test fun stopProgressIncludesLiveWorkButNeverFinishesEarly() {
        var clock = 0L
        val meter = CaptureMetrics { clock }
        meter.started(); repeat(200) { meter.samples(ShortArray(800) { 1000 }, true) }
        clock = 5000; meter.processed(80000, 2000)
        assertNull(meter.state.value.percent)
        meter.stopping(); meter.captureEnded()
        assertEquals(50, meter.state.value.percent)
        assertEquals(2.0, meter.state.value.remainingSeconds!!, 0.01)
        meter.processed(160000, 2000)
        clock += 100; meter.tick()
        assertEquals(99, meter.state.value.percent)
        meter.completed(null)
        assertEquals(100, meter.state.value.percent)
    }
    @Test fun queueBudgetDoesNotExpandWhenBlocksChangeSize(): Unit = runBlocking {
        val queue = BoundedAudioQueue(capacity = 1000, maximumSamples = 3200)
        repeat(4) { assertTrue(queue.offer(ShortArray(800))) }
        assertFalse(queue.offer(ShortArray(800)))
        queue.blocks.receive()
        assertTrue(queue.offer(ShortArray(800)))
        assertFalse(queue.offer(ShortArray(3200)))
        queue.close()
    }
}
