package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptRepinTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun pinningAnExpiredCopyReportsAbsenceWithoutRecreatingIt() {
        var now = 1000L
        val history = TranscriptHistory(folder.newFolder()) { now }
        val text = folder.newFile().apply { writeText("retained working text") }
        val saved = history.save(text, "Model")
        now += HistoryRetention.HOUR.millis
        history.prune(HistoryRetention.HOUR)
        assertNull(history.setPinnedIfPresent(saved.id, true, HistoryRetention.HOUR, "launch"))
        assertNull(history.setPinnedIfPresent(saved.id, false, HistoryRetention.HOUR, "launch"))
        assertTrue(history.list().isEmpty())
        assertTrue(text.isFile)
        assertFalse(saved.directory.exists())
    }

    @Test fun explicitlySavingANewCopyNeverRepinsADeletedLeasedId() {
        val history = TranscriptHistory(folder.newFolder())
        val text = folder.newFile().apply { writeText("Speaker 1: working text") }
        val saved = history.save(text, "Model", "missing-audio", created = 123, durationMs = 456, speakerLabels = true)
        history.acquire(saved.id).use {
            history.delete(saved.id)
            assertNull(history.setPinnedIfPresent(saved.id, true, HistoryRetention.HOUR, "launch"))
            assertNull(history.setPinnedIfPresent(saved.id, false, HistoryRetention.HOUR, "launch"))
            val replacement = history.save(text, saved.model, saved.audioId, pinned = true, attempt = "explicit-new-save",
                created = saved.created, durationMs = saved.durationMs, speakerLabels = saved.speakerLabels)
            assertNotEquals(saved.id, replacement.id)
            assertEquals(listOf(replacement.id), history.list().map { it.id })
            assertTrue(replacement.retention.pinned)
            assertTrue(saved.file.isFile)
            assertThrows(IllegalStateException::class.java) { history.get(saved.id) }
            assertEquals(saved.created, replacement.created)
            assertEquals(saved.durationMs, replacement.durationMs)
            assertTrue(replacement.speakerLabels)
            assertEquals(text.readText(), replacement.file.readText())
        }
        assertFalse(saved.directory.exists())
    }

    @Test fun presentEntryStillUsesTheNormalPinAndImmediateUnpinPolicy() {
        val history = TranscriptHistory(folder.newFolder())
        val saved = history.save(folder.newFile().apply { writeText("working text") }, "Model")
        assertTrue(history.setPinnedIfPresent(saved.id, true, HistoryRetention.NONE, "launch")!!.retention.pinned)
        val unpinned = history.setPinnedIfPresent(saved.id, false, HistoryRetention.NONE, "launch")!!
        assertEquals("launch", unpinned.retention.holdForLaunch)
        assertFalse(unpinned.retention.pinned)
        history.prune(HistoryRetention.NONE)
        assertEquals(saved.id, history.list().single().id)
    }
}
