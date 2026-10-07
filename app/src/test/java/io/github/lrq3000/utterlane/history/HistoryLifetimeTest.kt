package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryLifetimeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retainedRecoveryExpiresButTemporaryCrashRecoverySurvives() {
        var now = 1000L
        val history = RecordingHistory(temporary.root) { now }
        val retained = history.begin(HistoryRetention.HOUR)
        retained.append(shortArrayOf(1)); retained.finish(true)
        val working = history.begin(HistoryRetention.NONE)
        working.append(shortArrayOf(2)); working.finish(true)
        now += HistoryRetention.HOUR.millis
        history.prune(HistoryRetention.HOUR)
        assertFalse("Recovery is not an expiration exemption for saved history", retained.entry.directory.exists())
        assertTrue(working.entry.directory.exists())
    }

    @Test fun explicitDeletionCannotResurrectAfterAProcessDiesBeforeReaderRelease() {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        recording.append(shortArrayOf(1)); recording.finish(true)
        val lease = history.acquire(recording.entry.id)
        try {
            history.delete(recording.entry.id)
            val restarted = RecordingHistory(temporary.root)
            restarted.initialize()
            assertTrue("A persisted discard must not become crash recovery", restarted.list().isEmpty())
        } finally { lease.close() }
    }
}
