package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.TranscriptDiscardedException
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptRepinTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun freshExplicitKeepCannotOverrideConfirmedSourceDiscardOrRemoveSiblingMetadata() {
        val root = folder.newFolder()
        val history = TranscriptHistory(root)
        val text = folder.newFile().apply { writeText("Speaker 1: independently owned result") }
        val store = TranscriptStore(text)
        val saved = history.save(text, "Model", "audio", created = 0, durationMs = 456, speakerLabels = true)
        store.attachSource(TranscriptSource("audio", saved.id))
        val sibling = history.save(text, "Other model", "audio", attempt = "sibling", created = 123, durationMs = 789)
        try {
            // The reader's lease keeps bytes present, but confirmed deletion owns
            // source disposition. A random new Keep ID is not permission to undo it.
            TranscriptStore.deleteArtifacts(text)
            history.delete(saved.id)
            assertTrue(text.isFile)
            assertThrows(TranscriptDiscardedException::class.java) {
                history.save(text, "Model", "audio", pinned = true, attempt = "explicit-keep",
                    created = 0, durationMs = 456, speakerLabels = true)
            }
            assertEquals(listOf(sibling.id), history.forAudio("audio").map { it.id })
            val restarted = TranscriptHistory(root).forAudio("audio").single()
            assertEquals(sibling, restarted)
            assertEquals("Speaker 1: independently owned result", restarted.file.readText())
        } finally { store.keepForRecovery() }
    }

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
