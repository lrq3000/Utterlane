package io.github.lrq3000.utterlane.archive

import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.transcribe.QueueJournal
import io.github.lrq3000.utterlane.transcribe.QueueStage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PausedConversionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun conversionWhilePausedKeepsExactSuffixAcrossColdStartAndContinue() = pausedConversion(continueToEnd = true)
    @Test fun conversionDuringPauseHandoffKeepsOriginalUntilStoppedCheckpointIsDeleted() = pausedConversion(continueToEnd = false)

    private fun pausedConversion(continueToEnd: Boolean) {
        val queueRoot = temp.newFolder()
        val recordings = temp.newFolder()
        val archive = temp.newFolder()
        var journal = QueueJournal(queueRoot)
        var jobs = ArchivalJobStore(archive)
        fun openHistory() = RecordingHistory(recordings, jobs).apply {
            awaitingQueueReview = journal::protectsAudio
            queueNeedsOriginal = journal::protectsOriginal
        }
        var history = openHistory()
        val recording = history.begin(HistoryRetention.FOREVER)
        recording.append(shortArrayOf(10, 20, 30, 40)); recording.finish(false)
        val id = recording.entry.id
        val row = journal.add(id, "lecture", false, QueueStage.PROCESSING)
        val processing = history.beginProcessing(id)
        val original = recording.entry.part(0)
        history.planImportEncoding(id, AudioQuality(compress = true), jobs.currentEpoch())
        val encode = jobs.claim()!!
        val checkpoint = row.copy(stage = QueueStage.PAUSED, checkpointed = true, resumeSamples = 2)
        journal.update(row.copy(stage = QueueStage.PAUSING))
        processing.complete() // The clean pause handoff intentionally leaves no failed history attempt or live audio lease.
        if (continueToEnd) journal.update(checkpoint)

        val spool = RollingPcmJournal.open(recording.entry.directory)
        spool.append(shortArrayOf(10, 20, 30, 40)); spool.closeCapture()
        File(recording.entry.directory, "audio.frames").writeBytes(byteArrayOf(1))
        val encoded = File(recording.entry.directory, encode.targetName).apply { writeBytes(byteArrayOf(7, 8)) }
        assertTrue(jobs.target(encode.id, encode.generation, encoded.toURI().toString()))
        assertTrue(jobs.ready(encode.id, encode.generation))
        assertTrue(history.publishArchive(encode))
        fun assertOriginal() {
            assertFalse(history.hasLeases(id))
            while (history.cleanupPublishedEncoding()) { }
            assertTrue("Cleanup retired the paused input", original.isFile)
            history.acquire(id).use { lease ->
                lease.originalPcmReader(2).use { assertArrayEquals(shortArrayOf(30, 40), it.read()) }
            }
        }
        assertOriginal()
        if (!continueToEnd) journal.update(checkpoint)
        jobs.close()
        journal = QueueJournal(queueRoot)
        jobs = ArchivalJobStore(archive)
        history = openHistory().also { it.initialize() }
        assertOriginal()
        val idleWake = journal.originalReleases.value
        if (continueToEnd) {
            journal.update(checkpoint.continued())
            assertOriginal()
            journal.update(checkpoint.copy(stage = QueueStage.PROCESSING))
            assertOriginal()
            history.beginProcessing(id).complete()
            journal.update(checkpoint.copy(stage = QueueStage.READY))
            // Completed review still holds the recording, but no longer needs this exact input representation.
            assertTrue(journal.protectsAudio(id))
        } else {
            journal.update(checkpoint.copy(stage = QueueStage.STOPPING))
            journal.update(checkpoint.copy(stage = QueueStage.STOPPED))
            assertTrue(journal.protectsAudio(id))
            assertOriginal()
            assertTrue(journal.acknowledge(row.id))
        }
        assertNotEquals("A sleeping archival drain needs a wake on the last release", idleWake, journal.originalReleases.value)
        while (history.cleanupPublishedEncoding()) { }
        assertFalse(original.exists())
        assertTrue(encoded.isFile)
        history.acquire(id).use { assertNull(it.originalSource) }
        jobs.close()
    }

    @Test fun endingOneOfTwoCheckpointsCannotReleaseItsSiblingOrHoldAnUnstartedFile() {
        val journal = QueueJournal(temp.root)
        val first = journal.add("same", "first attempt", false, QueueStage.PROCESSING)
        val second = journal.add("same", "second attempt", false)
        journal.update(second.copy(stage = QueueStage.PAUSED, checkpointed = true))
        val other = journal.add("different", "not started", false)
        journal.update(other.copy(stage = QueueStage.PAUSED))
        assertFalse(journal.protectsOriginal(other.audio))
        journal.update(first.copy(stage = QueueStage.STOPPED))
        assertTrue(journal.acknowledge(first.id))
        assertTrue(journal.protectsOriginal(second.audio))
        journal.update(second.copy(stage = QueueStage.READY, checkpointed = true))
        assertFalse(journal.protectsOriginal(second.audio))
    }
}
