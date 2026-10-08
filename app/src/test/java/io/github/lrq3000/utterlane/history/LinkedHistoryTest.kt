package io.github.lrq3000.utterlane.history

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinkedHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun audio(history: RecordingHistory): HistoryEntry {
        val recording = history.begin(HistoryRetention.DAY)
        recording.append(shortArrayOf(1, 2, 3)); recording.finish(false)
        return history.get(recording.entry.id)
    }
    private fun text(history: TranscriptHistory, audio: HistoryEntry, attempt: String): TranscriptEntry =
        history.save(folder.newFile().apply { writeText("Result from $attempt") }, attempt, audio.id, attempt = attempt)

    @Test fun reverseLinksSurviveRestartAndExcludeLeasedDeletedResults() {
        val recordings = RecordingHistory(folder.newFolder())
        val root = folder.newFolder()
        val texts = TranscriptHistory(root)
        val source = audio(recordings)
        val first = text(texts, source, "first")
        val second = text(texts, source, "second")
        val other = text(texts, audio(recordings), "unrelated")
        val restarted = TranscriptHistory(root)
        assertEquals(setOf(first.id, second.id), restarted.forAudio(source.id).map { it.id }.toSet())
        restarted.acquire(first.id).use {
            restarted.delete(first.id)
            assertTrue(first.file.exists())
            assertEquals(listOf(second.id), restarted.forAudio(source.id).map { it.id })
        }
        assertFalse(first.file.exists())
        assertTrue(other.file.exists())
        assertEquals(listOf(second.id), TranscriptHistory(root).forAudio(source.id).map { it.id })
    }

    @Test fun transcriptOriginBothDeletesOnlyCurrentVersionAndAudio() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val source = audio(recordings)
        val current = text(texts, source, "current")
        val better = text(texts, source, "better-model")
        val history = LinkedHistory(recordings, texts)
        val plan = history.plan(source.id, current.id, transcriptOrigin = true)
        assertFalse(plan.allLinked)
        assertEquals(setOf(current.id), plan.transcriptIds)
        assertEquals(HistoryDeletionTarget.entries.toList(), plan.choices)
        assertTrue("Planning is read-only; confirmation is a separate action", current.file.exists())
        history.delete(plan, HistoryDeletionTarget.BOTH)
        assertFalse(source.directory.exists())
        assertFalse(current.file.exists())
        assertTrue(better.file.exists())
        assertEquals(listOf(better.id), texts.forAudio(source.id).map { it.id })
    }

    @Test fun recordingOriginIncludesAllLinkedVersionsButNeverOtherRecordings() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val source = audio(recordings)
        val first = text(texts, source, "first")
        val second = text(texts, source, "second")
        val other = text(texts, audio(recordings), "other")
        val history = LinkedHistory(recordings, texts)
        val plan = history.plan(source.id, first.id, transcriptOrigin = false)
        assertTrue(plan.allLinked)
        assertEquals(setOf(first.id, second.id), plan.transcriptIds)
        history.delete(plan, HistoryDeletionTarget.TRANSCRIPTS)
        assertTrue(source.part(0).exists())
        assertFalse(first.file.exists()); assertFalse(second.file.exists())
        assertTrue(other.file.exists())
    }

    @Test fun confirmedIdsNeverExpandToResultsCreatedAfterTheQuestion() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val source = audio(recordings)
        val first = text(texts, source, "first")
        val history = LinkedHistory(recordings, texts)
        val plan = history.plan(source.id, first.id, transcriptOrigin = false)
        val later = text(texts, source, "later")
        history.delete(plan, HistoryDeletionTarget.BOTH)
        assertFalse(first.file.exists()); assertTrue(later.file.exists())
    }

    @Test fun availabilityIgnoresDiscardedLeasedAudioAndMissingTextFiles() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val source = audio(recordings)
        val current = text(texts, source, "current")
        val history = LinkedHistory(recordings, texts)
        recordings.acquire(source.id).use {
            recordings.delete(source.id)
            assertTrue(source.part(0).exists())
            assertEquals(listOf(HistoryDeletionTarget.TRANSCRIPTS),
                history.plan(source.id, current.id, transcriptOrigin = true).choices)
            current.file.delete()
            assertTrue(history.plan(source.id, current.id, transcriptOrigin = true).choices.isEmpty())
        }
    }

    @Test fun missingAudioStillLinksAllVersionsAndCurrentWorkingResultDeduplicates() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val source = audio(recordings)
        val current = text(texts, source, "current")
        val history = LinkedHistory(recordings, texts)
        assertEquals(1, history.plan(source.id, current.id, false, current.id).transcriptIds.size)
        recordings.delete(source.id)
        val plan = history.plan(source.id, current.id, false, "unsaved-result")
        assertEquals(setOf(current.id, "unsaved-result"), plan.transcriptIds)
        assertEquals(listOf(HistoryDeletionTarget.TRANSCRIPTS), plan.choices)
    }

    @Test fun audioOnlyDeletionPreservesEveryLinkedTranscript() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val source = audio(recordings)
        val current = text(texts, source, "current")
        val history = LinkedHistory(recordings, texts)
        history.delete(history.plan(source.id, current.id, false), HistoryDeletionTarget.AUDIO)
        assertFalse(source.directory.exists())
        assertTrue(current.file.exists())
    }

    @Test fun audioOnlyOffersOneChoiceAndAbsentChoiceCannotDelete() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val source = audio(recordings)
        val history = LinkedHistory(recordings, texts)
        val plan = history.plan(source.id, null, false)
        assertEquals(listOf(HistoryDeletionTarget.AUDIO), plan.choices)
        assertThrows(IllegalArgumentException::class.java) { history.delete(plan, HistoryDeletionTarget.BOTH) }
        assertTrue(source.part(0).exists())
    }
}
