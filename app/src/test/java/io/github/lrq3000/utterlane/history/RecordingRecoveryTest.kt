package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unfinishedTemporaryAudioSurvivesStartupCleanupBeforeAnyFinalMetadata() {
        val first = RecordingHistory(temporary.root)
        val recording = first.begin(HistoryRetention.NONE)
        recording.append(shortArrayOf(1, 2, 3))
        try {
            val reopened = RecordingHistory(temporary.root)
            reopened.prune(HistoryRetention.NONE)
            assertEquals(1, reopened.recoveryCount())
            val recovered = reopened.list().single()
            assertTrue(recovered.temporary)
            assertTrue(recovered.needsRecovery)
            assertArrayEquals(shortArrayOf(1, 2, 3), reopened.read(recovered.id, 0, 3))
        } finally { recording.finish(true) }
    }

    @Test fun historyOffStillSpoolsInputAndDeletesSuccessfulRecording() {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        assertNotNull("History retention must not disable the live audio spool", recording)
        recording!!.append(shortArrayOf(1, 2, 3))
        assertArrayEquals(shortArrayOf(1, 2, 3), history.read(recording.entry.id, 0, 3))
        recording.finish(false)
        assertFalse(recording.entry.directory.exists())
        assertTrue(history.list().isEmpty())
    }

    @Test fun failedInputSurvivesDisabledHistoryPruningAndRestart() {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        recording.append(shortArrayOf(7, 8, 9))
        recording.finish(true)
        history.prune(HistoryRetention.NONE)
        val reopened = RecordingHistory(temporary.root)
        reopened.prune(HistoryRetention.NONE)
        assertEquals("Unfinished audio must remain visible for recovery", 1, reopened.list().size)
        assertArrayEquals(shortArrayOf(7, 8, 9), reopened.read(recording.entry.id, 0, 3))
        reopened.delete(recording.entry.id)
        assertFalse(recording.entry.directory.exists())
    }
}
