package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readerFollowsPublicationAndHoldsDeletionLease() {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        val reader = history.openReader(recording.entry.id)
        try {
            assertTrue(reader.read().isEmpty())
            recording.append(shortArrayOf(1, 2))
            assertArrayEquals(shortArrayOf(1, 2), reader.read())
            assertTrue(reader.read().isEmpty())
            recording.append(shortArrayOf(3))
            recording.finish(false)
            assertTrue(recording.entry.directory.exists())
            assertArrayEquals(shortArrayOf(3), reader.read())
            assertEquals(3L, reader.offset)
        } finally { reader.close() }
        assertFalse("Remaining files: ${recording.entry.directory.list()?.toList()}; visible: ${history.list()}", recording.entry.directory.exists())
        assertThrows(IllegalStateException::class.java) { reader.read() }
    }

    @Test fun successfulRetryResolvesOnlyTheUnfinishedRecording() {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        recording.append(shortArrayOf(1))
        recording.finish(true)
        history.openReader(recording.entry.id).use {
            history.completeRecovery(recording.entry.id, HistoryRetention.NONE)
            assertArrayEquals(shortArrayOf(1), it.read())
            history.dismiss(recording.entry.id)
            assertTrue(history.list().isEmpty())
        }
        assertFalse(recording.entry.directory.exists())
    }
}
