package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.transcribe.QueueJournal
import io.github.lrq3000.utterlane.transcribe.QueueRetryRegistration
import io.github.lrq3000.utterlane.transcribe.QueueStage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test fun transcriptOnlyImportIsPlayableButNeverOfferedAsSavedAudioUntilExplicitlyKept() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val linked = LinkedHistory(recordings, texts)
        val owned = recordings.importAudioOwned(byteArrayOf(5, 4).inputStream(), "opus", AudioImportMetadata(), ImportRetentionChoice.DISCARD)
        owned.lease.use {
            val id = owned.entry.id
            val saved = text(texts, owned.entry, "import")
            recordings.completeRecovery(id, HistoryRetention.DAY)
            for (textOrigin in listOf(false, true)) {
                assertNotNull(linked.availableAudio(id))
                assertEquals(listOf(HistoryDeletionTarget.TRANSCRIPTS), linked.plan(id, saved.id, textOrigin).choices)
            }
            recordings.setPinned(id, true, HistoryRetention.DAY, "launch")
            assertEquals(HistoryDeletionTarget.entries.toList(), linked.plan(id, saved.id, true).choices)
        }
    }

    @Test fun aPausedSiblingInvalidatesOpenHistoryAudioOrBothConsentBeforeAnyRemoval() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val queue = QueueJournal(folder.newFolder())
        recordings.awaitingQueueReview = queue::protectsAudio
        val source = audio(recordings)
        val saved = text(texts, source, "ordinary-history")
        val history = LinkedHistory(recordings, texts)
        fun plan() = history.plan(source.id, saved.id, transcriptOrigin = true)
        val open = plan()
        assertEquals(HistoryDeletionTarget.entries.toList(), open.choices)
        // Keep the source-registration monitor until the other thread has started confirming the already-open question. Both operations use the live repositories.
        val pool = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val answer = synchronized(recordings) {
                val future = pool.submit<HistoryDeletionPlan?> {
                    started.countDown()
                    history.confirmDeletion(open, HistoryDeletionTarget.BOTH, ::plan) { fail("A stale Both selection must not discard the displayed text") }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val sibling = QueueRetryRegistration(recordings, queue).add(source.id, false, null, "sibling")
                queue.update(sibling.copy(stage = QueueStage.PAUSED))
                future
            }
            assertEquals(listOf(HistoryDeletionTarget.TRANSCRIPTS), answer.get(5, TimeUnit.SECONDS)?.choices)
            assertEquals(listOf(HistoryDeletionTarget.TRANSCRIPTS), plan().choices)
            for (target in listOf(HistoryDeletionTarget.AUDIO, HistoryDeletionTarget.BOTH)) {
                assertThrows(IllegalStateException::class.java) { history.delete(open, target) }
            }
            assertTrue(saved.file.exists()); assertTrue(source.part(0).exists())
            assertNull(history.confirmDeletion(plan(), HistoryDeletionTarget.TRANSCRIPTS, ::plan) {})
            assertFalse(saved.file.exists()); assertTrue(source.part(0).exists())
        } finally { pool.shutdownNow() }
    }

    @Test fun ordinaryAllLinkedDeletionCannotRemoveNewQueueTargetOrItsKeepAlias() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val queue = QueueJournal(folder.newFolder())
        val results = folder.newFolder()
        fun target(id: String) = File(results, "$id.txt")
        val history = LinkedHistory(recordings, texts, queue, ::target)
        recordings.awaitingQueueReview = queue::protectsAudio
        val audio = audio(recordings)
        val ordinary = text(texts, audio, "ordinary")
        val id = java.util.UUID.randomUUID().toString()
        val store = TranscriptStore(target(id)).also { it.append("queue partial") }
        val deterministic = texts.save(store.file, "model", audio.id)
        val manual = texts.saveWorking(store, "model", audio.id, null, TranscriptMetadata())
        val aliasView = folder.newFile().apply { writeText("queue partial"); TranscriptSource(audio.id, manual.id).write(this) }
        try {
            fun plan() = history.plan(audio.id, ordinary.id, false, linkedWorkingIds = setOf(manual.id, deterministic.id))
            val opened = plan()
            assertEquals(setOf(ordinary.id, manual.id, deterministic.id), opened.transcriptIds)
            // The recording-origin view was already open when the queue published this exact attempt.
            queue.add(audio.id, "active", false, QueueStage.PAUSED, id)
            assertTrue(history.isQueueTarget(store.file))
            assertFalse(history.isQueueTarget(File(aliasView.parentFile, store.file.name)))
            assertThrows(IllegalStateException::class.java) { history.delete(opened, HistoryDeletionTarget.TRANSCRIPTS) }
            val renewed = history.confirmDeletion(opened, HistoryDeletionTarget.TRANSCRIPTS, ::plan) {
                fail("A stale ordinary question must not mark either the target or its cache alias")
            }!!
            assertEquals(setOf(ordinary.id), renewed.transcriptIds)
            assertNull(history.confirmDeletion(renewed, HistoryDeletionTarget.TRANSCRIPTS, ::plan) { ids ->
                if (manual.id in ids) TranscriptStore.deleteArtifacts(aliasView)
            })
            assertNull(texts.find(ordinary.id))
            assertTrue(aliasView.isFile); assertFalse(TranscriptSource.read(store.file).discarded)
            assertNotNull(texts.find(deterministic.id)); assertNotNull(texts.find(manual.id))
            assertTrue(audio.part(0).exists())
        } finally { store.keepForRecovery() }
    }

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

    @Test fun aDeletedWorkingResultCannotBePublishedByALateProducer() {
        val recordings = RecordingHistory(folder.newFolder())
        val texts = TranscriptHistory(folder.newFolder())
        val source = audio(recordings)
        val store = TranscriptStore(folder.newFile())
        store.attachSource(TranscriptSource(audioId = source.id))
        store.append("An in-flight result")
        val lease = store.acquire()
        try {
            val history = LinkedHistory(recordings, texts)
            val plan = history.plan(source.id, null, false, TranscriptHistory.idForAttempt(store.file.name))
            store.dispose()
            history.delete(plan, HistoryDeletionTarget.TRANSCRIPTS)
            assertTrue(store.file.exists())
            assertThrows(IllegalStateException::class.java) { texts.save(store.file, "Late producer", source.id) }
            assertTrue(texts.forAudio(source.id).isEmpty())
        } finally { lease.close() }
    }
}
