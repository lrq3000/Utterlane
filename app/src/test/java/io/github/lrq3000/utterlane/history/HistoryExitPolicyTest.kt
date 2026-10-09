package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryExitPolicyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun recoveryHandoffUsesOriginalAgeAndSurvivesRestartOnlyUntilDue() {
        var now = 100L
        val history = RecordingHistory(folder.root) { now }
        val capture = history.begin(HistoryRetention.NONE)
        capture.append(shortArrayOf(1)); capture.finish(true)
        now += 1000
        assertTrue(history.retainOnExit(capture.entry.id, HistoryExitPolicy(true, HistoryRetention.HOUR)))
        val reopened = RecordingHistory(folder.root) { now }
        assertEquals(100L, reopened.list().single().reference)
        assertTrue(reopened.list().single().recovered)
        now = 100L + HistoryRetention.HOUR.millis
        reopened.prune(HistoryRetention.HOUR)
        assertTrue(reopened.list().isEmpty())
    }

    @Test fun disabledImmediateAndExpiredTemporaryDataRequireConfirmationButPinsDoNot() {
        for (policy in listOf(HistoryExitPolicy(false, HistoryRetention.FOREVER),
            HistoryExitPolicy(true, HistoryRetention.NONE), HistoryExitPolicy(true, HistoryRetention.HOUR))) {
            assertFalse(policy.keeps(RetentionMark(0), stored = false, now = HistoryRetention.HOUR.millis))
            assertTrue(policy.keeps(RetentionMark(0, pinned = true), stored = false, now = Long.MAX_VALUE))
        }
        // Disabling automatic capture does not remove already saved history.
        assertTrue(HistoryExitPolicy(false, HistoryRetention.HOUR).keeps(RetentionMark(0), stored = true, now = 1))
    }

    @Test fun viewedAudioCanStillBePinnedAfterExpiryButExplicitDeletionRemainsAuthoritative() {
        var now = 0L
        val history = RecordingHistory(folder.root) { now }
        val capture = history.begin(HistoryRetention.HOUR)
        capture.append(shortArrayOf(1)); capture.finish(false)
        history.acquire(capture.entry.id, protectFromPruning = true).use {
            now = HistoryRetention.HOUR.millis
            history.prune(HistoryRetention.HOUR)
            assertEquals(1, history.list().size)
            history.setPinned(capture.entry.id, true, HistoryRetention.HOUR, "launch")
            history.delete(capture.entry.id)
            assertTrue(history.list().isEmpty())
        }
        assertFalse(capture.entry.directory.exists())
    }

    @Test fun viewingTranscriptDefersExpiryUntilTheOwnerMakesAnExitChoice() {
        var now = 0L
        val history = TranscriptHistory(folder.newFolder()) { now }
        val entry = history.save(folder.newFile().apply { writeText("Words") }, "model")
        history.acquire(entry.id, protectFromPruning = true).use {
            now = HistoryRetention.HOUR.millis
            history.prune(HistoryRetention.HOUR)
            assertNotNull(history.find(entry.id))
        }
        history.prune(HistoryRetention.HOUR)
        assertNull(history.find(entry.id))
    }
}
