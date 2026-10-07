package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import kotlin.math.log10
import kotlin.math.sqrt

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

    @Test fun callbacksAdvanceIndependentFinishedPointsBeforeOneHundredMilliseconds() {
        val waveform = WaveformHistory()
        waveform.accept(ShortArray(160) { 2000 })
        val first = waveform.snapshot()
        val copy = first.clone()
        waveform.accept(ShortArray(160) { 16000 })
        val second = waveform.snapshot()
        assertTrue("Initial PCM must be visible without waiting for an aggregation bucket", first.last() > 0f)
        assertEquals("The earlier callback must move left, not be averaged into a changing tip",
            first.last(), second[62], 0f)
        val loudOnly = WaveformHistory().apply { accept(ShortArray(160) { 16000 }) }.snapshot().last()
        assertEquals("The new point must retain this callback's full amplitude", loudOnly, second.last(), 0f)
        assertArrayEquals(copy, first, 0f)
        assertSame(second, waveform.snapshot())
    }

    @Test fun waveformMatchesOriginalCallbackMeasurementsAcrossWraparounds() {
        val waveform = WaveformHistory()
        val reference = OriginalWaveform()
        val sizes = listOf(1, 73, 160, 320, 800, 1600, 3200)
        repeat(200) { block ->
            // Include short/large reads, silence, both PCM extrema, and varying
            // samples within a block: callback RMS must not become peak amplitude.
            val pcm = ShortArray(sizes[block % sizes.size]) { sample -> when (block % 5) {
                0 -> 0
                1 -> Short.MAX_VALUE
                2 -> Short.MIN_VALUE
                else -> ((block * 257 + sample * 13) % 65536 - 32768).toShort()
            } }
            waveform.accept(pcm); reference.accept(pcm)
            assertArrayEquals("Original waveform after callback $block", reference.snapshot(), waveform.snapshot(), 0.000001f)
        }
        val old = waveform.snapshot()
        val copy = old.clone()
        waveform.accept(ShortArray(0))
        assertSame("No new PCM must not insert an artificial point", old, waveform.snapshot())
        waveform.accept(ShortArray(160) { 24000 })
        assertArrayEquals(copy, old, 0f)
        assertNotSame(old, waveform.snapshot())
    }

    @Test fun publicationCadenceNeverStretchesOrDropsCallbackHistory() {
        for (rate in listOf(1, 2, 5, 10, 20, 30, 60, 90, 200)) {
            for (blockMs in listOf(10, 20, 50)) {
                var now = 0L
                val metrics = CaptureMetrics { now }
                metrics.setVisualRefreshRate(rate)
                val reference = OriginalWaveform()
                var previous = metrics.state.value
                var samples = 0L
                repeat(2000 / blockMs) { block ->
                    now += blockMs
                    val pcm = ShortArray(blockMs * 16) { (1000 + block * 100).toShort() }
                    reference.accept(pcm)
                    metrics.samples(pcm, true)
                    samples += pcm.size
                    val current = metrics.state.value
                    if (current !== previous) {
                        assertArrayEquals("$rate Hz / $blockMs ms callbacks at $now ms",
                            reference.snapshot(), current.waveform, 0.000001f)
                        assertEquals(samples, current.capturedSamples)
                        previous = current
                    }
                }
                // Catch up once after a low-rate deadline. History advances on
                // input, including callbacks never individually shown in a frame.
                now += 1000; metrics.tick()
                assertArrayEquals(reference.snapshot(), metrics.state.value.waveform, 0.000001f)
                assertEquals(32000L, metrics.state.value.capturedSamples)
                val waveform = metrics.state.value.waveform
                now += 1000; metrics.tick()
                assertSame("A display tick without PCM must not scroll the history", waveform, metrics.state.value.waveform)
            }
        }
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

    /** Pre-limiter CaptureMetrics (1237054) amplitude and callback semantics.
     * A chronological deque makes this oracle independent of ring cursor/snapshot
     * indexing, catching both aggregation changes and rollover mistakes. */
    private class OriginalWaveform {
        private val levels = ArrayDeque<Float>()
        fun accept(pcm: ShortArray) {
            if (pcm.isEmpty()) return
            var squares = 0.0
            pcm.forEach { val sample = it / 32768.0; squares += sample * sample }
            val rms = sqrt(squares / pcm.size)
            val db = 20 * log10(rms.coerceAtLeast(1e-7))
            levels.addLast(if (rms == 0.0) 0f else ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat())
            if (levels.size > 64) levels.removeFirst()
        }
        fun snapshot(): FloatArray = FloatArray(64 - levels.size) + levels.toFloatArray()
    }
}
