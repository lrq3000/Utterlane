package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueueAttemptDeletionTest {
    @get:Rule val folder = TemporaryFolder()
    private val recordings by lazy { RecordingHistory(folder.newFolder()) }
    private val texts by lazy { TranscriptHistory(folder.newFolder()) }
    private val journal by lazy { QueueJournal(folder.newFolder()) }
    private val results by lazy { folder.newFolder() }
    private val cache by lazy { folder.newFolder() }
    private val external by lazy { folder.newFile().apply { writeText("external original") } }
    private val deletion by lazy { QueueAttemptDeletion(recordings, texts, journal, ::target, cache) }
    private fun target(id: String) = File(results, "$id.txt")
    private fun row(choice: ImportRetentionChoice): QueueRecord {
        recordings.awaitingQueueReview = journal::protectsAudio
        val owned = external.inputStream().use { recordings.importAudioOwned(it, "opus", AudioImportMetadata(), choice) }
        return owned.lease.use { journal.add(owned.entry.id, "test.opus", false) }
    }
    private fun question(job: QueueRecord) = checkNotNull(deletion.question(job.id))
    private fun stopped(id: String): Boolean {
        val job = journal.find(id) ?: return false
        journal.update(job.copy(stage = QueueStage.FAILED))
        return true
    }
    private fun acknowledge(id: String) {
        val job = checkNotNull(journal.find(id))
        assertTrue(journal.acknowledge(id))
        recordings.refreshQueueHold(job.audio)
    }

    @Test fun cancelBeforeFirstTextWaitsForStopAndRetiresStagingAfterItsFinalOwner() = runBlocking {
        val job = row(ImportRetentionChoice.DISCARD)
        val audio = recordings.get(job.audio)
        val view = recordings.acquire(audio.id)
        val plan = question(job)
        assertFalse(plan.canDeleteAudio)
        assertEquals(QueueStage.WAITING, journal.find(job.id)?.stage)
        assertThrows(IllegalStateException::class.java) { runBlocking { deletion.confirm(plan, false, { false }, ::acknowledge) } }
        assertEquals(QueueStage.WAITING, journal.find(job.id)?.stage)
        assertTrue(audio.part(0).exists())
        // An interrupted producer can have empty text with only a provenance sidecar.
        TranscriptSource(audioId = audio.id).write(target(job.id))
        deletion.confirm(plan, false, ::stopped, ::acknowledge)
        assertNull(journal.find(job.id))
        assertFalse(TranscriptSource.metadata(target(job.id)).exists())
        assertTrue(audio.part(0).exists())
        view.close()
        assertFalse(audio.directory.exists())
        assertEquals("external original", external.readText())
    }

    @Test fun newSiblingAfterQuestionProtectsItsSourceAndAllSiblingOutputs() = runBlocking {
        val job = row(ImportRetentionChoice.KEEP)
        val plan = question(job)
        assertTrue(plan.canDeleteAudio)
        val sibling = journal.add(job.audio, "retry", false)
        val own = target(job.id).apply { writeText("delete only me") }
        val other = target(sibling.id).apply { writeText("keep this version") }
        texts.save(own, "old", job.audio)
        texts.save(other, "new", job.audio)
        val revised = checkNotNull(deletion.confirm(plan, true, ::stopped, ::acknowledge))
        assertFalse(revised.canDeleteAudio)
        assertNotNull(texts.find(job.textId)); assertTrue(own.isFile)
        assertNull(deletion.confirm(revised, false, ::stopped, ::acknowledge))
        assertNull(journal.find(job.id)); assertNotNull(journal.find(sibling.id))
        assertNull(texts.find(job.textId)); assertFalse(own.exists())
        assertEquals("keep this version", texts.get(sibling.textId).file.readText())
        assertTrue(other.isFile); assertTrue(recordings.get(job.audio).part(0).exists())
    }

    @Test fun manualKeepDuringStopRequiresFreshConsentAndRemovesOnlyThatAttemptsCopies() = runBlocking {
        val job = row(ImportRetentionChoice.KEEP)
        val store = TranscriptStore(target(job.id))
        store.attachSource(TranscriptSource(audioId = job.audio))
        store.append("partial result")
        val plan = question(job)
        val revised = checkNotNull(deletion.confirm(plan, false, { id ->
            texts.saveWorking(store, "current", job.audio, null, TranscriptMetadata())
            stopped(id)
        }, ::acknowledge))
        val saved = checkNotNull(store.source.transcriptId)
        val view = File(cache, "view-copy.txt").apply { writeText("partial result") }
        TranscriptSource(job.audio, saved).write(view)
        assertNotNull(texts.find(saved)); assertFalse(TranscriptSource.read(store.file).discarded)
        assertNull(deletion.confirm(revised, false, ::stopped, ::acknowledge))
        assertTrue(TranscriptSource.read(store.file).discarded)
        assertNull(texts.find(saved)); assertFalse(view.exists())
        store.keepForRecovery()
        assertFalse(store.file.exists()); assertFalse(TranscriptSource.metadata(store.file).exists())
        assertTrue(recordings.get(job.audio).part(0).exists())
    }

    @Test fun audioOptInRemovesOnlyPrivateCopyAndTerminalRow() = runBlocking {
        val job = row(ImportRetentionChoice.KEEP)
        val source = recordings.get(job.audio)
        assertNull(deletion.confirm(question(job), true, ::stopped, ::acknowledge))
        assertFalse(source.directory.exists())
        assertEquals("external original", external.readText())
        assertNull(journal.find(job.id))
    }

    @Test fun protectedStagingSurvivesFirstDeletionAndDoesNotLeakAfterLastCancellation() = runBlocking {
        val first = row(ImportRetentionChoice.DISCARD)
        val source = recordings.get(first.audio)
        val other = journal.add(source.id, "second", false)
        recordings.beginProcessing(source.id).close() // An interrupted attempt leaves a recovery obligation.
        assertNull(deletion.confirm(question(first), false, ::stopped, ::acknowledge))
        assertTrue(recordings.get(source.id).part(0).exists()); assertNotNull(journal.find(other.id))
        assertNull(deletion.confirm(question(other), false, ::stopped, ::acknowledge))
        assertNull(journal.find(other.id))
        assertFalse(recordings.hasLeases(source.id))
        if (source.directory.exists()) {
            // Windows can temporarily defer unlinking a tombstoned directory. A live source or stranded processing obligation must still fail before exercising the normal cleanup retry.
            assertEquals("discarded", recordings.get(source.id).status)
            assertThrows(IllegalStateException::class.java) { recordings.acquire(source.id) }
            recordings.prune(HistoryRetention.FOREVER)
        }
        assertFalse(source.directory.exists())
    }

    @Test fun simultaneousCancellationsDoNotBothLeaveStagingForTheOtherOwner() = runBlocking {
        val a = row(ImportRetentionChoice.DISCARD)
        val b = journal.add(a.audio, "second", false)
        val source = recordings.get(a.audio)
        val firstQuestion = question(a)
        val secondQuestion = question(b)
        val reachedAck = CompletableDeferred<Unit>()
        val releaseAck = CompletableDeferred<Unit>()
        val gate = QueueDisposition()
        val otherDialog = QueueAttemptDeletion(recordings, texts, journal, ::target, cache)
        val one = async {
            gate.run {
                deletion.confirm(firstQuestion, false, ::stopped) { id ->
                    reachedAck.complete(Unit); releaseAck.await(); acknowledge(id)
                }
            }
        }
        withTimeout(5000) { reachedAck.await() }
        val two = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run { otherDialog.confirm(secondQuestion, false, ::stopped, ::acknowledge) }
        }
        assertEquals("B must not change its disposition until A's durable row is retired", QueueStage.WAITING, journal.find(b.id)?.stage)
        releaseAck.complete(Unit)
        assertNull(one.await()); assertNull(two.await())
        assertTrue(journal.records().isEmpty())
        assertFalse(source.directory.exists())
    }

    @Test fun liveCaptureAndDrainNeverOfferDeletionAndANonterminalStopCannotEraseOutput() = runBlocking {
        val job = row(ImportRetentionChoice.KEEP)
        val plan = question(job)
        val output = target(job.id).apply { writeText("authoritative words") }
        for (stage in listOf(QueueStage.CAPTURING, QueueStage.DRAINING)) {
            journal.update(job.copy(stage = stage))
            assertNull(deletion.question(job.id))
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { deletion.confirm(plan, true, { true }, ::acknowledge) } }
        assertEquals("authoritative words", output.readText())
        assertTrue(recordings.get(job.audio).part(0).exists())
    }

    @Test fun pinningAnImportWhileTheQuestionIsOpenNeverDeletesNewlyKeptAudio() = runBlocking {
        val job = row(ImportRetentionChoice.DISCARD)
        val plan = question(job)
        recordings.setPinned(job.audio, true, HistoryRetention.DAY, "launch")
        val fresh = checkNotNull(deletion.confirm(plan, false, ::stopped, ::acknowledge))
        assertTrue(fresh.canDeleteAudio)
        assertNotNull(journal.find(job.id))
        assertTrue(recordings.get(job.audio).pinned)
        assertNull(deletion.confirm(fresh, false, ::stopped, ::acknowledge))
        assertTrue(recordings.get(job.audio).pinned)
    }

    @Test fun failedRetirementCanRetryAfterPayloadCleanupWithoutDeletingSibling() = runBlocking {
        val job = row(ImportRetentionChoice.KEEP)
        val sibling = journal.add(job.audio, "other", false)
        target(job.id).writeText("remove me")
        target(sibling.id).writeText("preserve me")
        assertThrows(java.io.IOException::class.java) {
            runBlocking { deletion.confirm(question(job), false, ::stopped) { throw java.io.IOException("notification withdrawal failed") } }
        }
        assertTrue(checkNotNull(journal.find(job.id)).stage.reviewable)
        assertNull(deletion.confirm(question(job), false, ::stopped, ::acknowledge))
        assertNull(journal.find(job.id)); assertNotNull(journal.find(sibling.id))
        assertEquals("preserve me", target(sibling.id).readText())
    }
}
