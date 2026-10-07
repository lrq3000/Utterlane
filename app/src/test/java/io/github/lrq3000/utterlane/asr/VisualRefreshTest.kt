package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class VisualRefreshTest {
    @Test fun fileProgressTicksDoNotReportAMicrophoneInterruption() {
        var now = 0L
        val metrics = CaptureMetrics { now }
        metrics.captured(16000)
        now = 5000
        metrics.tick()
        assertEquals(CaptureSignal.WAITING, metrics.state.value.signal)
        assertEquals(16000L, metrics.state.value.capturedSamples)
    }

    @Test fun tenSecondsOfDenseCapturePublishesOnlyTheSelectedVisualWorkBudget() {
        for (rate in listOf(1, 2, 5, 10, 20, 30, 60, 90, 200)) {
            var now = 0L
            val metrics = CaptureMetrics { now }
            metrics.setVisualRefreshRate(rate)
            metrics.samples(ShortArray(1600) { 3000 }, true)
            var previous = metrics.state.value
            var publications = 0
            repeat(10000) {
                now++
                metrics.samples(ShortArray(16) { 3000 }, true)
                if (metrics.state.value !== previous) { publications++; previous = metrics.state.value }
            }
            assertEquals("$rate Hz display budget during 10000 capture callbacks", rate * 10, publications)
            metrics.captureEnded()
            assertEquals("Visual coalescing must not discard input counts", 161600L, metrics.state.value.capturedSamples)
        }
    }

    @Test fun defaultCadencePublishesSixtyTimesPerSecond() {
        var now = 0L
        val metrics = CaptureMetrics { now }
        metrics.samples(ShortArray(1600) { 3000 }, true)
        var previous = metrics.state.value
        var publications = 0
        repeat(1000) {
            now++
            metrics.samples(ShortArray(16) { 3000 }, true)
            if (metrics.state.value !== previous) { publications++; previous = metrics.state.value }
        }
        assertEquals(60, publications)
    }

    @Test fun partialAudioBucketProvidesFreshFeedbackBeforeOneHundredMilliseconds() {
        val waveform = WaveformHistory()
        waveform.accept(ShortArray(160) { 2000 })
        val first = waveform.snapshot()
        val copy = first.clone()
        waveform.accept(ShortArray(160) { 16000 })
        val second = waveform.snapshot()
        assertTrue("Initial PCM must be visible without waiting for a full history bucket", first.last() > 0f)
        assertTrue("New audio must update the live tip at high refresh rates", second.last() > first.last())
        assertArrayEquals(copy, first, 0f)
        assertSame(second, waveform.snapshot())
    }

    @Test fun waveformUsesAudioTimeRatherThanBlockOrRefreshFrequency() {
        val whole = WaveformHistory()
        val fragmented = WaveformHistory()
        val pcm = ShortArray(1600 * 80) { if (it < 1600 * 30) 18000 else 3000 }
        whole.accept(pcm)
        for (offset in pcm.indices step 73) fragmented.accept(pcm.copyOfRange(offset, minOf(offset + 73, pcm.size)))
        assertArrayEquals(whole.snapshot(), fragmented.snapshot(), 0f)
        assertSame(fragmented.snapshot(), fragmented.snapshot())
        val old = fragmented.snapshot()
        val oldValues = old.clone()
        fragmented.accept(ShortArray(1600) { 24000 })
        assertArrayEquals(oldValues, old, 0f)
        assertNotSame(old, fragmented.snapshot())
    }

    @Test fun oneHzRateLimitsRoutineUpdatesButSignalsAndCompletionAreImmediate() {
        var now = 0L
        val metrics = CaptureMetrics { now }
        metrics.setVisualRefreshRate(1)
        metrics.samples(ShortArray(1600) { 3000 }, true)
        val initial = metrics.state.value
        repeat(9) {
            now += 100
            metrics.samples(ShortArray(1600) { 3000 }, true)
            assertSame(initial, metrics.state.value)
        }
        now = 1000; metrics.tick()
        assertEquals(16000L, metrics.state.value.capturedSamples)
        metrics.silenced(true)
        assertEquals(CaptureSignal.BLOCKED, metrics.state.value.signal)
        metrics.completed(null)
        assertEquals(100, metrics.state.value.percent)
    }
    @Test fun unchangedAudioSignalReusesPublishedWaveformUntilRefreshDeadline() {
        var now = 0L
        val metrics = CaptureMetrics { now }
        metrics.setVisualRefreshRate(10)
        metrics.samples(ShortArray(1600) { 4000 }, true)
        val first = metrics.state.value
        repeat(9) {
            now += 10
            metrics.samples(ShortArray(160) { 4000 }, true)
            assertSame("PCM callbacks should not rebuild the presentation", first, metrics.state.value)
        }
        now = 100
        metrics.tick()
        assertNotSame(first, metrics.state.value)
        assertEquals(3040L, metrics.state.value.capturedSamples)
        assertEquals(64, metrics.state.value.waveform.size)
    }

    @Test fun endingCaptureImmediatelyFlushesCountsAndDoesNotAlterOldWaveform() {
        var now = 0L
        val metrics = CaptureMetrics { now }
        metrics.samples(ShortArray(1600) { 4000 }, true)
        val previous = metrics.state.value.waveform
        val copy = previous.clone()
        metrics.samples(ShortArray(1600) { 20000 }, true)
        metrics.captureEnded()
        assertEquals(3200L, metrics.state.value.capturedSamples)
        assertArrayEquals(copy, previous, 0f)
        assertEquals(CapturePhase.PROCESSING, metrics.state.value.phase)
    }
}
