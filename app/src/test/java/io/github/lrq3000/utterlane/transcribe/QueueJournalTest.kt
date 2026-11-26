package io.github.lrq3000.utterlane.transcribe

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.lrq3000.utterlane.history.*
import java.io.File

class QueueJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun reviewSecondAfterRestartPreservesOtherResultsAndTheirOriginalOrder() {
        val journal = QueueJournal(temp.root)
        val first = journal.add("audio-a", "First", false)
        val second = journal.add("audio-b", "Second", false)
        val third = journal.add("audio-c", "Third", true)
        journal.update(first.copy(stage = QueueStage.READY))
        journal.update(second.copy(stage = QueueStage.READY))
        val restored = QueueJournal(temp.root)
        assertEquals(listOf("First", "Second", "Third"), restored.records().map { it.label })
        assertTrue(restored.acknowledge(second.id))
        assertFalse(restored.acknowledge(third.id))
        assertEquals(listOf(first.id, third.id), QueueJournal(temp.root).records().map { it.id })
        assertTrue(restored.protectsAudio("audio-a"))
        assertFalse(restored.protectsAudio("audio-b"))
    }
    @Test fun completedTranscriptOnlyInputSurvivesLastLeaseAndColdStartUntilReviewed() {
        val journal = QueueJournal(File(temp.root, "queue"))
        val directory = File(temp.root, "audio")
        val recordings = RecordingHistory(directory).apply { awaitingQueueReview = journal::protectsAudio }
        val source = recordings.importAudioOwned(byteArrayOf(1, 2, 3).inputStream(), "ogg",
            AudioImportMetadata("original.ogg"), ImportRetentionChoice.DISCARD)
        val row = journal.add(source.entry.id, "original.ogg", false)
        recordings.beginProcessing(row.audio).use { it.complete() }
        source.lease.close()
        val restoredJournal = QueueJournal(File(temp.root, "queue"))
        val restored = RecordingHistory(directory).apply { awaitingQueueReview = restoredJournal::protectsAudio }
        restored.prune(HistoryRetention.NONE)
        assertArrayEquals(byteArrayOf(1, 2, 3), restored.get(row.audio).part(0).readBytes())
        restoredJournal.update(row.copy(stage = QueueStage.READY))
        assertTrue(restoredJournal.acknowledge(row.id))
        restored.refreshQueueHold(row.audio)
        assertFalse(source.entry.directory.exists())
    }
    @Test fun reviewOfRetryCannotReleaseSharedSourceOrOverwriteFirstAttempt() {
        val journal = QueueJournal(temp.root)
        val a = journal.add("shared", "Recording", false)
        val b = journal.add("shared", "Retry", false)
        journal.update(a.copy(stage = QueueStage.READY)); journal.update(b.copy(stage = QueueStage.FAILED))
        assertTrue(journal.anotherOwnsAudio("shared", b.id))
        assertTrue(journal.acknowledge(b.id))
        assertFalse(journal.anotherOwnsAudio("shared", a.id))
        assertTrue(journal.protectsText(a.textId))
        assertFalse(journal.protectsText(b.textId))
    }
    @Test fun newAttemptReopensWithRequestedModelAndSpeakerSetting() {
        val initial = QueueJournal(temp.root)
        val old = initial.add("recording", "Interview", true, QueueStage.READY)
        val retry = initial.add(old.audio, old.label, true, modelId = "selected-for-this-attempt")
        val restored = QueueJournal(temp.root)
        assertEquals("selected-for-this-attempt", restored.find(retry.id)?.modelId)
        assertEquals(true, restored.find(retry.id)?.speakers)
        assertEquals(QueueStage.READY, restored.find(old.id)?.stage)
    }
}
