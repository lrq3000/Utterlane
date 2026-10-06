package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class CaptureBacklogTest {
    @Test fun backlogUsesAcceptedSamplesAndProcessedOwnershipNotWallTime() {
        val metrics = CaptureMetrics()
        metrics.samples(ShortArray(16000), true)
        metrics.samples(ShortArray(8000), false)
        metrics.processed(4000, 9000)
        assertEquals(1.0, metrics.state.value.capturedSeconds, 0.0)
        assertEquals(0.25, metrics.state.value.processedSeconds, 0.0)
        assertEquals(0.75, metrics.state.value.backlogSeconds, 0.0)
        metrics.processed(-10, 0)
        assertEquals(0.75, metrics.state.value.backlogSeconds, 0.0)
        metrics.processed(Long.MAX_VALUE, 0)
        assertEquals(0.0, metrics.state.value.backlogSeconds, 0.0)
    }

    @Test fun fileCountsAndWorkerCompletionCannotFinalizeCapture() {
        val metrics = CaptureMetrics()
        metrics.captured(32000)
        metrics.captureEnded()
        metrics.processed(32000, 100)
        metrics.recognition(RecognitionActivity(stage = "completed"))
        assertEquals(99, metrics.state.value.percent)
        metrics.completed(null)
        metrics.recognition(RecognitionActivity(stage = "queued", active = true))
        assertEquals(100, metrics.state.value.percent)
        assertEquals(CapturePhase.COMPLETE, metrics.state.value.phase)
        assertFalse(metrics.state.value.recognition.active)
    }

    @Test fun failureEndsACollectedActiveStageWithoutShowingCompletion() {
        val metrics = CaptureMetrics()
        metrics.recognition(RecognitionActivity(stage = "asr", active = true, message = "opaque"))
        metrics.completed("failure")
        assertFalse(metrics.state.value.recognition.active)
        assertEquals(RecognitionStage.ERROR, metrics.state.value.recognition.stage)
        assertNotEquals(100, metrics.state.value.percent)
    }

    @Test fun processedCallbackRacingAcceptedCaptureDoesNotLoseOwnership() {
        val metrics = CaptureMetrics()
        // queue.offer wakes its consumer before the recorder publishes its metrics.
        metrics.processed(800, 20)
        assertEquals(0.0, metrics.state.value.backlogSeconds, 0.0)
        metrics.samples(ShortArray(800), true)
        assertEquals(800, metrics.state.value.processedSamples)
        assertEquals(0.0, metrics.state.value.backlogSeconds, 0.0)
    }
}
