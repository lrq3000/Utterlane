package io.github.lrq3000.utterlane.history

import io.github.lrq3000.utterlane.asr.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Two independent owners use the real publication and confirmation operations.
 * Monitor/latch boundaries control the race; no sleeps or fake save results. */
class TranscriptKeepPublicationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun otherOwnerCannotMarkBetweenKeepPublicationAndProvenanceAttachment() {
        val texts = TranscriptHistory(folder.newFolder())
        val recordings = RecordingHistory(folder.newFolder())
        val file = folder.newFile().apply { writeText("Working result") }
        val keeper = TranscriptStore(file)
        val deleter = TranscriptStore(file)
        val oldId = TranscriptHistory.idForAttempt(file.name)
        val sibling = texts.save(folder.newFile().apply { writeText("Other version") }, "Other model", "audio")
        val keepStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val deletion = LinkedHistory(recordings, texts)
            val original = deletion.plan("audio", null, true, oldId)
            lateinit var keepThread: Thread
            lateinit var deleteThread: Thread
            val deletionStarted = CountDownLatch(1)
            val saved: java.util.concurrent.Future<TranscriptEntry>
            val confirmed: java.util.concurrent.Future<HistoryDeletionPlan?>
            synchronized(keeper) {
                saved = pool.submit<TranscriptEntry> {
                    keepThread = Thread.currentThread(); keepStarted.countDown()
                    texts.saveWorking(keeper, "Model", "audio", null, TranscriptMetadata())
                }
                assertTrue(keepStarted.await(5, TimeUnit.SECONDS))
                awaitBlocked(keepThread, "attachSource") // save N completed; attach N has not.
                confirmed = pool.submit<HistoryDeletionPlan?> {
                    deleteThread = Thread.currentThread(); deletionStarted.countDown()
                    deletion.confirmDeletion(original, HistoryDeletionTarget.TRANSCRIPTS,
                        refresh = {
                            val source = TranscriptSource.read(deleter.file)
                            deletion.plan("audio", null, true, source.transcriptId ?: oldId)
                        }, discardWorking = { TranscriptStore.deleteArtifacts(deleter.file) })
                }
                assertTrue(deletionStarted.await(5, TimeUnit.SECONDS))
                // The old implementation finishes deletion here, marks W, then
                // attach N throws and leaves a pinned orphan. Fixed confirmation
                // must wait for this other owner's complete publication instead.
                awaitFinishedOrBlocked(confirmed, deleteThread)
                assertFalse("Deletion marked the source before Keep attached its new identity", confirmed.isDone)
            }
            val replacement = saved.get(5, TimeUnit.SECONDS)
            val renewed = confirmed.get(5, TimeUnit.SECONDS)
            assertEquals(setOf(replacement.id), renewed!!.transcriptIds)
            assertFalse(renewed.allLinked)
            assertFalse(TranscriptSource.read(file).discarded)
            assertTrue(texts.get(replacement.id).retention.pinned)
            assertNotNull(texts.find(sibling.id))
            assertNull(deletion.confirmDeletion(renewed, HistoryDeletionTarget.TRANSCRIPTS,
                refresh = { deletion.plan("audio", replacement.id, true, TranscriptSource.read(file).transcriptId) },
                discardWorking = { TranscriptStore.deleteArtifacts(file) }))
            assertNull(texts.find(replacement.id))
            assertEquals(listOf(sibling.id), texts.forAudio("audio").map { it.id })
        } finally {
            pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
            keeper.keepForRecovery(); deleter.keepForRecovery()
        }
    }

    @Test fun failedProvenancePublicationRollsBackOnlyTheNewKeepCopy() {
        val texts = TranscriptHistory(folder.newFolder())
        val store = TranscriptStore(folder.newFile().apply { writeText("Result") })
        val sibling = texts.save(folder.newFile().apply { writeText("Saved sibling") }, "Other", "audio")
        // A directory at the sidecar destination permits source reads (isFile is
        // false) but makes the real atomic provenance rename fail after saving.
        TranscriptSource.metadata(store.file).mkdir()
        try {
            assertThrows(Exception::class.java) { texts.saveWorking(store, "Model", "audio", null, TranscriptMetadata()) }
            assertEquals("A failed Keep must not leave a pinned orphan", listOf(sibling.id), texts.list().map { it.id })
        } finally { store.keepForRecovery() }
    }

    @Test fun deletionThatRechecksFirstRejectsTheOtherOwnersLaterKeep() {
        val texts = TranscriptHistory(folder.newFolder())
        val deletion = LinkedHistory(RecordingHistory(folder.newFolder()), texts)
        val file = folder.newFile().apply { writeText("Result before confirmation") }
        val keeper = TranscriptStore(file)
        val deleter = TranscriptStore(file)
        val id = TranscriptHistory.idForAttempt(file.name)
        val plan = deletion.plan(null, null, true, id)
        val checked = CountDownLatch(1)
        val mark = CountDownLatch(1)
        val started = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val confirmed = pool.submit<HistoryDeletionPlan?> {
                deletion.confirmDeletion(plan, HistoryDeletionTarget.TRANSCRIPTS, refresh = {
                    checked.countDown()
                    check(mark.await(5, TimeUnit.SECONDS))
                    plan
                }, discardWorking = { TranscriptStore.deleteArtifacts(deleter.file) })
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            lateinit var keepThread: Thread
            val keep = pool.submit<TranscriptEntry> {
                keepThread = Thread.currentThread(); started.countDown()
                texts.saveWorking(keeper, "Model", null, null, TranscriptMetadata())
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            awaitBlocked(keepThread, "saveWorking")
            mark.countDown()
            assertNull(confirmed.get(5, TimeUnit.SECONDS))
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { keep.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is TranscriptDiscardedException)
            assertTrue(texts.list().isEmpty())
        } finally {
            mark.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
            keeper.keepForRecovery(); deleter.keepForRecovery()
        }
    }

    private fun awaitBlocked(thread: Thread, method: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.BLOCKED && thread.stackTrace.any { it.methodName == method }) return
            Thread.yield()
        }
        fail("Owner never blocked in $method")
    }

    private fun awaitFinishedOrBlocked(future: java.util.concurrent.Future<*>, thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (future.isDone || thread.state == Thread.State.BLOCKED) return
            Thread.yield()
        }
        fail("Deletion neither finished nor blocked")
    }
}
