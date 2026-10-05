package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class RecognitionStatusTest {
    @Test fun fixedStagesPreserveSpeakerWorkAndOpaqueEntry() {
        val entry = RecognitionStatus.from(RecognitionActivity(stage = "speaker_inference", active = true,
            elapsedMillis = 8000, sinceProgressMillis = 4000, completedUnits = 12, message = "opaque"))
        assertEquals(RecognitionStage.SPEAKERS, entry.stage)
        assertTrue(entry.opaque)
        assertEquals(8000, entry.elapsedMillis)
        assertEquals(4000, entry.sinceProgressMillis)
        val callback = RecognitionStatus.from(RecognitionActivity(stage = "speaker/transformer", active = true,
            elapsedMillis = 10000, sinceProgressMillis = 0, completedUnits = 13))
        assertEquals(RecognitionStage.SPEAKER_TRANSFORMER, callback.stage)
        assertFalse(callback.opaque)
        assertEquals(RecognitionStage.SPEAKER_FEATURES, RecognitionStatus.from(
            RecognitionActivity(stage = "speaker_load/features", active = true)).stage)
    }

    @Test fun arbitraryNativeNamesAndMessagesDoNotCrossTheContentFreeBoundary() {
        val status = RecognitionStatus.from(RecognitionActivity(stage = "../../private\ntranscript", message = "private words", active = true))
        assertEquals("unknown", status.scope)
        assertTrue(status.opaque)
        assertFalse(status.toString().contains("private"))
        assertEquals(RecognitionStage.ERROR, RecognitionStatus.from(RecognitionActivity(message = "secret")).stage)
    }

    @Test fun workerFinishedMeansOnlyRequestFinishedAndCountersStayNonnegative() {
        val status = RecognitionStatus.from(RecognitionActivity(stage = "completed", elapsedMillis = -1, sinceProgressMillis = -2))
        assertEquals(RecognitionStage.FINISHED, status.stage)
        assertEquals(0, status.elapsedMillis)
        assertEquals(0, status.sinceProgressMillis)
        assertFalse(status.active)
    }
}
