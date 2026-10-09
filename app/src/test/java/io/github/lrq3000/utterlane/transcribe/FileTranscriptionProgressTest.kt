package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.asr.CaptureSnapshot
import org.junit.Assert.*
import org.junit.Test

class FileTranscriptionProgressTest {
    private fun capture(processed: Long = 160000, accepted: Long = 320000, rate: Double? = 0.000025) =
        CaptureSnapshot(capturedSamples = accepted, processedSamples = processed, processingSecondsPerSample = rate)

    @Test fun decodedAheadInputDoesNotCountAsCompletedTranscription() {
        val progress = FileTranscriptionProgress(640000).apply { start(false) }
        val snapshot = progress.snapshot(capture())
        assertEquals(25, snapshot.percent)
        assertEquals(12.0, snapshot.remainingSeconds!!, 0.001)
        assertEquals(160000, snapshot.processedSamples)
    }

    @Test fun knownDurationStillNeedsMeasuredThroughputForAnEta() {
        val progress = FileTranscriptionProgress(640000).apply { start(false) }
        assertEquals(25, progress.snapshot(capture(rate = null)).percent)
        assertNull(progress.snapshot(capture(rate = null)).remainingSeconds)
    }

    @Test fun unknownTotalBecomesExactOnlyAtInputEof() {
        val progress = FileTranscriptionProgress().apply { start(false) }
        assertNull(progress.snapshot(capture()).percent)
        assertNull(progress.snapshot(capture()).remainingSeconds)
        progress.inputEnded(320000)
        val snapshot = progress.snapshot(capture())
        assertEquals(50, snapshot.percent)
        assertFalse(snapshot.estimatedTotal)
        assertEquals(4.0, snapshot.remainingSeconds!!, 0.001)
    }

    @Test fun underestimatedMetadataIsInvalidatedInsteadOfHoldingAtNinetyNine() {
        val progress = FileTranscriptionProgress(200000, estimatedTotal = true).apply { start(false) }
        assertTrue(progress.snapshot(capture(80000, 100000)).estimatedTotal)
        val overrun = progress.snapshot(capture())
        assertNull(overrun.totalSamples)
        assertNull(overrun.percent)
        assertNull(overrun.remainingSeconds)
        progress.inputEnded(320000)
        assertEquals(50, progress.snapshot(capture()).percent)
    }

    @Test fun overestimatedMetadataIsReplacedByExactDecodedSamples() {
        val progress = FileTranscriptionProgress(640000, estimatedTotal = true).apply { start(false) }
        assertTrue(progress.snapshot(capture()).estimatedTotal)
        progress.inputEnded(320000)
        assertEquals(320000L, progress.snapshot(capture()).totalSamples)
        assertFalse(progress.snapshot(capture()).estimatedTotal)
    }

    @Test fun exhaustedInputAndFinishingStagesNeverClaimCompleteOrZeroSeconds() {
        val progress = FileTranscriptionProgress(320000).apply { start(true); inputEnded(320000) }
        val capture = capture(320000)
        assertEquals(FileProgressStage.FINALIZING, progress.snapshot(capture).stage)
        assertNull(progress.snapshot(capture).percent)
        assertNull(progress.snapshot(capture).remainingSeconds)
        progress.finalizing(true)
        assertEquals(FileProgressStage.SPEAKERS, progress.snapshot(capture).stage)
        progress.saving()
        assertEquals(FileProgressStage.SAVING, progress.snapshot(capture).stage)
        assertNull(progress.snapshot(capture).percent)
        progress.complete()
        assertEquals(100, progress.snapshot(capture).percent)
        assertEquals(FileProgressStage.COMPLETE, progress.snapshot(capture).stage)
    }

    @Test fun speakerFinishingIsExplicitlyOutsideTheWindowEstimate() {
        val progress = FileTranscriptionProgress(640000).apply { start(true) }
        assertTrue(progress.snapshot(capture()).additionalFinishing)
        assertEquals(12.0, progress.snapshot(capture()).remainingSeconds!!, 0.001)
    }

    @Test fun failureAndCancellationRetainWorkWithoutStaleEtaOrLateSuccess() {
        for (cancelled in listOf(false, true)) {
            val progress = FileTranscriptionProgress(640000).apply { start(false); fail(cancelled) }
            progress.complete(); progress.start(true); progress.saving(); progress.finalizing(true)
            val snapshot = progress.snapshot(capture())
            assertEquals(if (cancelled) FileProgressStage.CANCELLED else FileProgressStage.FAILED, snapshot.stage)
            assertEquals(160000, snapshot.processedSamples)
            assertNull(snapshot.remainingSeconds)
            assertNull(snapshot.percent)
        }
    }

    @Test fun snapshotsDoNotAdvanceBecauseTimeHasPassed() {
        val progress = FileTranscriptionProgress(640000).apply { start(false) }
        val snapshot = progress.snapshot(capture())
        repeat(100) { assertEquals(snapshot, progress.snapshot(capture())) }
    }

    @Test fun emptyInputWaitsForExplicitSuccess() {
        val progress = FileTranscriptionProgress().apply { start(false); inputEnded(0) }
        val capture = capture(0, 0)
        assertEquals(FileProgressStage.FINALIZING, progress.snapshot(capture).stage)
        assertNull(progress.snapshot(capture).percent)
        progress.complete()
        assertEquals(100, progress.snapshot(capture).percent)
    }

    @Test fun preparationAndInvalidRatesHaveNoMadeUpEta() {
        val progress = FileTranscriptionProgress(640000)
        assertNull(progress.snapshot(capture()).percent)
        assertNull(progress.snapshot(capture()).remainingSeconds)
        progress.start(false)
        for (rate in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertNull(progress.snapshot(capture(rate = rate)).remainingSeconds)
    }
}
