package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CaptureMetricsTest {
    @Test fun loadingAndFailureDoNotReplaceTheLiveCaptureState() {
        val meter = CaptureMetrics()
        meter.preparingModel()
        meter.started()
        meter.samples(shortArrayOf(1000), true)
        assertEquals(CapturePhase.CAPTURING, meter.state.value.phase)
        assertTrue(meter.state.value.modelLoading)
        meter.recognitionFailed("load failed")
        meter.samples(shortArrayOf(2000), true)
        assertEquals(CapturePhase.CAPTURING, meter.state.value.phase)
        assertFalse(meter.state.value.modelLoading)
        assertEquals("load failed", meter.state.value.recognitionError)
        assertEquals(2L, meter.state.value.capturedSamples)
        meter.stopping(); meter.captureEnded(); meter.completed("load failed")
        assertEquals(CapturePhase.FAILED, meter.state.value.phase)
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
