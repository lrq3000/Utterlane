package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryPagingRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun audioPagesExcludeActiveAndLeasedDeletedEntriesAndIgnorePinOrder() {
        var now = 0L
        val history = RecordingHistory(temp.newFolder()) { ++now }
        repeat(65) {
            history.begin(HistoryRetention.FOREVER).apply { append(shortArrayOf(100)); finish(false) }
        }
        val active = history.begin(HistoryRetention.FOREVER)
        val first = history.page()
        assertEquals(30, first.entries.size)
        assertFalse(first.entries.any { it.id == active.entry.id })
        val entry = first.entries[10]
        val lease = history.acquire(entry.id)
        try {
            history.setPinned(entry.id, true, HistoryRetention.DAY, "launch")
            assertEquals(first.entries.map { it.id }, history.page().entries.map { it.id })
            history.delete(entry.id)
            assertTrue(entry.directory.exists())
            assertFalse(history.page().entries.any { it.id == entry.id })
            assertEquals(30, history.page(first.after, HistoryDirection.APPEND).entries.size)
            assertEquals(5, history.page(history.page(first.after, HistoryDirection.APPEND).after, HistoryDirection.APPEND).entries.size)
        } finally { lease.close(); active.finish(false) }
        assertFalse(entry.directory.exists())
    }

    @Test fun transcriptPagesRetainIndependentOrderingAcrossPinsAndDeletion() {
        var now = 0L
        val history = TranscriptHistory(temp.newFolder()) { ++now }
        val source = File(temp.root, "source.txt").apply { writeText("Paging fixture") }
        repeat(61) { history.save(source, "model", attempt = "attempt-$it") }
        val first = history.page()
        val second = history.page(first.after, HistoryDirection.APPEND)
        assertEquals(30, second.entries.size)
        val last = history.page(second.after, HistoryDirection.APPEND)
        assertEquals(1, last.entries.size); assertNull(last.after)
        val entry = second.entries[12]
        history.setPinned(entry.id, true, HistoryRetention.DAY, "launch")
        assertEquals(second.entries.map { it.id }, history.page(first.after, HistoryDirection.APPEND).entries.map { it.id })
        history.acquire(entry.id).use {
            history.delete(entry.id)
            assertTrue(entry.file.exists())
            assertFalse(history.page(first.after, HistoryDirection.APPEND).entries.any { it.id == entry.id })
        }
        assertFalse(entry.file.exists())
        assertEquals(first.entries.map { it.id }, history.page(second.before, HistoryDirection.PREPEND).entries.map { it.id })
    }
}
