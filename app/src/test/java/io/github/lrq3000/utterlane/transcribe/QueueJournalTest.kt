package io.github.lrq3000.utterlane.transcribe

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.lrq3000.utterlane.history.*
import java.io.File

class QueueJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun legacyStoppedDescriptorCannotResumeFromItsPreviousPausePosition() {
        val journal = QueueJournal(temp.root)
        val row = journal.add("audio", "Legacy", false, QueueStage.STOPPED)
        val descriptor = File(temp.root, "${row.id}.job")
        val properties = io.github.lrq3000.utterlane.archive.ArchiveDisk.read(descriptor)
        properties.setProperty("checkpointed", "true")
        properties.setProperty("resumeSamples", "32001")
        io.github.lrq3000.utterlane.archive.AudioMetadata.write(descriptor, properties)

        val cold = QueueJournal(temp.root)
        assertEquals(QueueStage.STOPPED, cold.find(row.id)?.stage)
        assertFalse(cold.find(row.id)!!.canContinue)
        assertNull(cold.continueJob(row.id))
        assertTrue(cold.protectsAudio(row.audio))
    }

    @Test fun stoppedCheckpointIsNotDispatchedAndCanOnlyBeRequeuedExplicitly() {
        val journal = QueueJournal(temp.root)
        val row = journal.add("audio", "File", false, QueueStage.STOPPED)
        journal.update(row.copy(resumeSamples = 32_001, checkpointed = true, terminalPosted = true))
        val cold = QueueJournal(temp.root)
        assertNull(nextEligibleFile(cold.records(), "current"))
        assertEquals(emptyList<QueueRecord>(), QueueHistoryIndex(cold.records()).monitorRows)
        assertEquals(listOf(row.id), QueueHistoryIndex(cold.records()).withoutHistoryRows(emptySet()).map { it.id })
        assertTrue(cold.protectsOriginal(row.audio))
        val waiting = cold.continueJob(row.id)!!
        assertEquals(row.id, waiting.id)
        assertEquals(32_001L, waiting.resumeSamples)
        assertFalse(waiting.terminalPosted)
        assertEquals(waiting, nextEligibleFile(cold.records(), "current"))
    }

    @Test fun deletingStoppedAttemptReleasesOriginalWithoutRemovingSiblingAttempt() {
        val journal = QueueJournal(temp.root)
        val stopped = journal.add("audio", "Stopped", false, QueueStage.STOPPED)
        journal.update(stopped.copy(resumeSamples = 100L, checkpointed = true))
        val sibling = journal.add("audio", "Sibling", false, QueueStage.READY)
        assertTrue(journal.protectsOriginal(stopped.audio))
        assertTrue(journal.acknowledge(stopped.id))
        assertFalse(journal.protectsOriginal(stopped.audio))
        assertTrue(journal.protectsAudio(stopped.audio))
        assertEquals(sibling, journal.find(sibling.id))
    }

    @Test fun pausedFileIsNeverDispatchedByOtherFileOrMicCompletionEvenAfterReload() {
        val journal = QueueJournal(temp.root)
        val lecture = journal.add("lecture", "Lecture", false, QueueStage.PROCESSING)
        val interview = journal.add("interview", "Interview", false)
        val mic = journal.add("mic", "Dictation", false, QueueStage.DRAINING)
        assertNotNull(journal.transition(lecture.id, setOf(QueueStage.PROCESSING)) {
            it.copy(stage = QueueStage.PAUSED, resumeSamples = 73001, checkpointed = true)
        })
        journal.update(interview.copy(stage = QueueStage.READY))
        journal.finishCapture(mic.id, null, "interrupted", false)
        val restored = QueueJournal(temp.root)
        assertNull(restored.records().firstOrNull { it.stage == QueueStage.WAITING })
        assertNull(restored.transition(lecture.id, setOf(QueueStage.PROCESSING)) { it.copy(stage = QueueStage.READY) })
        assertEquals(73001L, restored.transition(lecture.id, setOf(QueueStage.PAUSED)) { it.copy(stage = QueueStage.WAITING) }!!.resumeSamples)
    }

    @Test fun resumeSkipsExactlyTheFlushedAudioAcrossDifferentDecoderBlockSizes() {
        val cursor = ResumeCursor(5L)
        assertEquals(0, cursor.unprocessed(shortArrayOf(1, 2, 3)).size)
        assertArrayEquals(shortArrayOf(6, 7), cursor.unprocessed(shortArrayOf(4, 5, 6, 7)))
        assertArrayEquals(shortArrayOf(8), cursor.unprocessed(shortArrayOf(8)))
        assertEquals(8L, cursor.position)
        cursor.requireReached()
        assertThrows(IllegalStateException::class.java) { ResumeCursor(5).requireReached() }
    }

    @Test fun manualPauseDoesNotDuplicateOrEraseTheCommittedPrefixAfterReopening() {
        val file = File(temp.root, "attempt.txt")
        val provenance = io.github.lrq3000.utterlane.asr.TranscriptSource("lecture", modelId = "model-A")
        val first = io.github.lrq3000.utterlane.asr.TranscriptStore(file)
        first.beginTranscription(provenance)
        first.append("first part")
        first.sync()
        first.finishProducing(); first.keepForRecovery()
        val next = io.github.lrq3000.utterlane.asr.TranscriptStore(file)
        assertThrows(IllegalStateException::class.java) { next.resumeTranscription("another-recording", "model-A") }
        next.resumeTranscription("lecture", "model-A")
        assertThrows(IllegalStateException::class.java) { next.resumeTranscription("lecture", "model-A") }
        next.append("remaining words")
        next.markComplete()
        next.finishProducing(); next.keepForRecovery()
        assertEquals("first part remaining words", file.readText())
        assertEquals(io.github.lrq3000.utterlane.asr.TranscriptCompletion.COMPLETE, io.github.lrq3000.utterlane.asr.TranscriptSource.read(file).completion)
    }

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

    @Test fun deferredModelSwitchDoesNotStarveCompatibleWaitingFileDuringDictation() {
        val journal = QueueJournal(temp.root)
        val first = journal.add("lecture", "Paused lecture", false, modelId = "old-model")
        val second = journal.add("note", "Note", false, modelId = "current-model")
        journal.add("capture", "Mic", true, QueueStage.DRAINING)
        assertEquals(second.id, nextEligibleFile(journal.records(), "current-model")?.id)
        journal.update(second.copy(stage = QueueStage.READY))
        assertNull(nextEligibleFile(journal.records(), "current-model"))
        assertEquals(first.id, nextEligibleFile(journal.records().filter { it.stage != QueueStage.DRAINING }, "current-model")?.id)
    }

    @Test fun rapidRestartRequestsRegisterOnlyOneSiblingForTheSelectedSource() {
        val journal = QueueJournal(temp.root)
        val source = journal.add("voice", "Interview", false, QueueStage.PROCESSING)
        val requests = QueueRestartRequests()
        val accepted = listOf(source.id, source.id).filter(requests::claim)
        journal.update(source.copy(stage = QueueStage.STOPPED))
        accepted.forEach { journal.add(source.audio, source.label, source.speakers); requests.release(it) }
        val next = journal.records().single { it.stage == QueueStage.WAITING }
        assertEquals(source.audio, next.audio)
        assertNotEquals(source.id, next.id)
        assertEquals(QueueStage.STOPPED, journal.find(source.id)?.stage)
        assertTrue(requests.claim(source.id))
    }

    @Test fun resumedDiarizationUsesANewLabelSectionOnlyAfterActualInputWasCheckpointed() {
        val journal = QueueJournal(temp.root)
        val row = journal.add("lecture", "Lecture", true, QueueStage.PAUSED)
        assertEquals(0, row.continued().speakerSection)
        val later = row.copy(resumeSamples = 1111L, checkpointed = true).continued()
        journal.update(later)
        assertEquals(1, QueueJournal(temp.root).find(row.id)!!.speakerSection)
        assertEquals(2, later.copy(stage = QueueStage.PAUSED).continued().speakerSection)
    }
}
