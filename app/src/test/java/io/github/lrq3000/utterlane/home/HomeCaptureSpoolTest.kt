package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.asr.CaptureMetrics
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.RecordingHistory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeCaptureSpoolTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun captureMetricsAheadOfTheWriterDoNotEstablishInputOwnership() {
        val history = RecordingHistory(folder.root)
        val recording = history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        val probe = HomeCaptureSpool(history)
        val metrics = CaptureMetrics()
        try {
            metrics.started()
            assertFalse(probe.hasPublishedSamples(recording.entry.id))
            metrics.samples(shortArrayOf(7, 8), true)
            metrics.captureEnded() // Force publication independently of visual throttling.
            assertEquals(2L, metrics.state.value.capturedSamples)
            assertFalse("Accepted/queued capture samples are not durable spool evidence", probe.hasPublishedSamples(recording.entry.id))
            recording.append(shortArrayOf(7, 8))
            assertEquals("History metadata stays at zero until finalization", 0L, history.get(recording.entry.id).samples)
            assertTrue("Accept during ongoing capture, without ASR or waiting for finish", probe.hasPublishedSamples(recording.entry.id))
        } finally { recording.finish(false); history.dismiss(recording.entry.id) }
    }

    @Test fun deletedOrDiscardedSourceIsNeverLiveInputEvidence() {
        val history = RecordingHistory(folder.root)
        val recording = history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        val probe = HomeCaptureSpool(history)
        assertFalse(probe.hasPublishedSamples("missing"))
        try {
            recording.append(shortArrayOf(7))
            history.dismiss(recording.entry.id) // Writer lease defers physical deletion.
            assertFalse(probe.hasPublishedSamples(recording.entry.id))
        } finally { recording.finish(false) }
        assertFalse(probe.hasPublishedSamples(recording.entry.id))
    }
}
