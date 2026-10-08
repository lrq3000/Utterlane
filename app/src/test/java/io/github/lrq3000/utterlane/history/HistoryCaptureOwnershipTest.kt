package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryCaptureOwnershipTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun completionRetainsLatestMetadataRatherThanTheCaptureSnapshot() {
        val history = RecordingHistory(folder.root)
        val recording = history.begin(HistoryRetention.HOUR)
        recording.append(shortArrayOf(1))
        history.recordFailure(recording.entry.id, "model unavailable", "MODEL")
        recording.finish(true)
        assertEquals("model unavailable", history.get(recording.entry.id).failureMessage)
    }

    @Test fun keptCompletionSurvivesImmediatePruningAndRestartUntilDismissed() {
        for ((retention, automatic) in listOf(HistoryRetention.HOUR to false, HistoryRetention.NONE to true)) {
            val root = folder.newFolder()
            val history = RecordingHistory(root)
            val recording = history.begin(retention, automatic, keepUntilDismissed = true)
            recording.append(ShortArray(16000))
            history.setSpeakerLabels(recording.entry.id, true)
            recording.finish(false)
            history.prune(HistoryRetention.NONE)
            val entry = history.list().single()
            assertTrue(entry.temporary)
            assertTrue("An unacknowledged result must remain discoverable after process loss", entry.needsRecovery)
            assertEquals(1000L, entry.durationMs)
            assertTrue(entry.speakerLabels)
            val restarted = RecordingHistory(root)
            restarted.prune(HistoryRetention.NONE)
            assertTrue(restarted.list().single().speakerLabels)
            assertEquals(1, restarted.recoveryCount())
            restarted.dismiss(entry.id)
            assertFalse(entry.directory.exists())
            assertTrue(restarted.list().isEmpty())
        }
    }

    @Test fun pinTransfersTemporaryOwnershipAndUnpinKeepsNormalLaunchHold() {
        val history = RecordingHistory(folder.root)
        val recording = history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recording.append(shortArrayOf(1)); recording.finish(false)
        history.setPinned(recording.entry.id, true, HistoryRetention.NONE, "first")
        history.dismiss(recording.entry.id)
        history.prune(HistoryRetention.NONE)
        assertFalse(history.get(recording.entry.id).temporary)
        assertTrue(history.get(recording.entry.id).pinned)
        history.setPinned(recording.entry.id, false, HistoryRetention.NONE, "first")
        history.prune(HistoryRetention.NONE)
        assertTrue(recording.entry.directory.exists())
        history.onUserLaunch("second"); history.prune(HistoryRetention.NONE)
        assertFalse(recording.entry.directory.exists())
    }

    @Test fun defaultCompletionStillDeletesWithoutHistoryAndEmptyKeptAudioIsDeleted() {
        val history = RecordingHistory(folder.root)
        for ((retention, automatic) in listOf(HistoryRetention.HOUR to false, HistoryRetention.NONE to true)) {
            val recording = history.begin(retention, automatic)
            recording.append(shortArrayOf(1)); recording.finish(false)
            assertFalse(recording.entry.directory.exists())
        }
        val empty = history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        empty.finish(false)
        assertFalse(empty.entry.directory.exists())
    }

    @Test fun keptAutomaticHistoryStillExpiresNormally() {
        var now = 1000L
        val history = RecordingHistory(folder.root) { now }
        val recording = history.begin(HistoryRetention.HOUR, true, keepUntilDismissed = true)
        recording.append(shortArrayOf(1)); recording.finish(false)
        assertFalse(history.get(recording.entry.id).temporary)
        assertFalse(history.get(recording.entry.id).needsRecovery)
        now += HistoryRetention.HOUR.millis
        history.prune(HistoryRetention.HOUR)
        assertFalse(recording.entry.directory.exists())
    }

    @Test fun discardedActiveResultCannotBeRepublishedByMetadataOrFinish() {
        val history = RecordingHistory(folder.root)
        val recording = history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recording.append(shortArrayOf(1))
        val lease = history.acquire(recording.entry.id)
        history.dismiss(recording.entry.id)
        history.setSpeakerLabels(recording.entry.id, true)
        recording.finish(false)
        assertTrue(history.list().isEmpty())
        assertEquals("discarded", history.get(recording.entry.id).status)
        assertFalse(history.get(recording.entry.id).speakerLabels)
        assertThrows(IllegalStateException::class.java) { history.acquire(recording.entry.id) }
        assertTrue(RecordingHistory(folder.root).list().isEmpty())
        lease.close()
        history.setSpeakerLabels(recording.entry.id, true)
        assertFalse(recording.entry.directory.exists())
    }

    @Test fun interruptedKeptCaptureRemainsRecoverableAfterRestart() {
        val history = RecordingHistory(folder.root)
        val recording = history.begin(HistoryRetention.NONE, keepUntilDismissed = true)
        recording.append(shortArrayOf(1, 2, 3))
        // Reopening the repository simulates process loss while active metadata is durable.
        val restarted = RecordingHistory(folder.root)
        try {
            restarted.prune(HistoryRetention.NONE)
            val entry = restarted.list().single()
            assertEquals("interrupted", entry.status)
            assertTrue(entry.temporary)
            assertTrue(entry.needsRecovery)
            assertEquals(3L, entry.samples)
        } finally { recording.finish(true) }
    }
}
