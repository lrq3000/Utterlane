package io.github.lrq3000.utterlane.archive

import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.io.Closeable
import io.github.lrq3000.utterlane.history.TranscriptHistory
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivalCoordinatorLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun one(): Boolean = if (queue.isEmpty()) false else { queue.removeFirst().run(); true }
    }
    private fun drain(main: QueueDispatcher, io: QueueDispatcher) {
        repeat(100) { if (!(main.one() or io.one())) return }
        fail("Archival event loop spun instead of waiting")
    }
    private fun request(owner: String, source: File) = ArchivalRequest(ArtifactOwner(ArtifactKind.TRANSCRIPT, owner),
        ArchivalOperation.SAVE_MARKDOWN, source, "content://p/tree/folder", "text.md", "text/markdown")

    @Test fun `cancel during claim return cannot strand reservation for stop timeout or plain cancellation`() {
        for (stop in listOf("cancel", "timeout", "plain")) {
            val main = QueueDispatcher(); val io = QueueDispatcher()
            val uncaught = mutableListOf<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, error -> uncaught.add(error) })
            val source = temp.newFile().apply { writeText("preserved input") }
            val journal = ArchivalJobStore(temp.newFolder())
            val old = journal.enqueue(request("old", source))
            var released: ArchivalJob? = null
            var entered = false
            val task = scope.launch {
                ArchivalExecution(journal, io).runNext({}, { released = it }, { throw it }) { entered = true }
            }
            try {
                assertTrue(main.one()) // Dispatch claim to IO.
                assertTrue(io.one()) // Claim finished; result queued for Main.
                val claimed = checkNotNull(journal.job(old.id))
                assertEquals(ArchivalState.RUNNING, claimed.state)
                when (stop) {
                    "cancel" -> journal.cancelAll()
                    "timeout" -> journal.pauseExecuting("Service timeout")
                }
                task.cancel()
                drain(main, io)
                assertFalse(entered)
                assertTrue(task.isCancelled)
                val fresh = journal.enqueue(request("fresh", source), journal.currentEpoch())
                val next = journal.claim()
                assertEquals("$stop left a live reservation", fresh.id, next?.id)
                assertEquals(claimed.id, released?.id)
                assertTrue(uncaught.isEmpty())
                assertEquals("preserved input", source.readText())
                // Late cleanup can settle only the exact old reservation.
                journal.releaseExecution(claimed.id, claimed.generation)
                assertNull(journal.claim())
                assertEquals(ArchivalState.RUNNING, journal.job(fresh.id)?.state)
            } finally { scope.cancel(); drain(main, io); journal.close() }
        }
    }

    @Test fun `maintenance failure is contained pauses wakeups and allows explicit retry with sibling healthy`() {
        val main = QueueDispatcher(); val io = QueueDispatcher()
        val uncaught = mutableListOf<Throwable>()
        val reported = mutableListOf<Exception>()
        val scope = CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        val wakes = Channel<Unit>(Channel.CONFLATED)
        val journalRoot = temp.newFolder()
        val journal = ArchivalJobStore(journalRoot)
        val source = temp.newFile().apply { writeText("capture remains usable") }
        val oldRequest = request("published-owner", source)
        journal.enqueue(oldRequest)
        val published = checkNotNull(journal.claim())
        journal.target(published.id, published.generation, "content://p/d/retained")
        journal.ready(published.id, published.generation)
        journal.publish(published.id, published.generation) {}
        journal.deleteOwner(oldRequest.owner)
        val marker = File(journalRoot, "deleting/${ArchiveDisk.owner(oldRequest.owner)}.deleted")
        val backup = File(temp.root, "deletion-marker-backup")
        check(marker.renameTo(backup)); check(marker.mkdir()) // Actual read IO fails inside maintain().
        val queued = journal.enqueue(request("owner", source))
        val boundary = ArchivalFailureBoundary(reported::add)
        var maintenanceCalls = 0
        var dispatched = 0
        var siblingTicks = 0
        val loop = scope.launch {
            consumeArchivalWakes(wakes, boundary) {
                withContext(io) {
                    maintenanceCalls++
                    journal.maintain()
                }
                ArchivalExecution(journal, io).runNext({}, {}, { throw it }) { claim ->
                    assertEquals(queued.id, claim.job.id)
                    dispatched++
                    journal.notCreated(claim.job.id, claim.job.generation)
                    journal.pause(claim.job.id, claim.job.generation, "Fixture completed without provider mutation")
                }
            }
        }
        try {
            wakes.trySend(Unit); drain(main, io)
            assertTrue("Optional IO escaped its application scope: $uncaught", uncaught.isEmpty())
            assertEquals(1, reported.size)
            assertTrue(boundary.paused)
            assertTrue(loop.isActive)
            assertEquals(1, journal.summary().queued)
            repeat(20) { wakes.trySend(Unit); scope.launch { siblingTicks++ }; drain(main, io) }
            assertEquals(1, maintenanceCalls)
            assertEquals(20, siblingTicks)
            assertEquals(0, dispatched)
            assertEquals("capture remains usable", source.readText())
            check(marker.delete()); check(backup.renameTo(marker))
            boundary.retry(); wakes.trySend(Unit); drain(main, io)
            assertEquals(1, dispatched)
            assertFalse(boundary.paused)
            assertTrue(uncaught.isEmpty())
            val cleanup = checkNotNull(journal.nextCleanup()) { "Retry lost the pending owner's published URI" }
            assertEquals("content://p/d/retained", cleanup.uri)
            journal.completeCleanup(cleanup.key)
        } finally { scope.cancel(); drain(main, io); journal.close() }
    }

    @Test fun `cancel during source lease return closes the real lease and frees its reservation`() {
        val main = QueueDispatcher(); val io = QueueDispatcher()
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        val journal = ArchivalJobStore(temp.newFolder())
        val history = TranscriptHistory(temp.newFolder(), journal)
        val source = temp.newFile().apply { writeText("complete input") }
        val entry = history.save(source, "model")
        journal.enqueue(request(entry.id, entry.file))
        val task = scope.launch {
            ArchivalExecution(journal, io).runNext({}, {}, { throw it }) { claim ->
                claim.acquireLease { history.acquire(entry.id) }
                fail("Cancelled lease return must not dispatch provider work")
            }
        }
        try {
            assertTrue(main.one()); assertTrue(io.one()) // claim
            assertTrue(main.one()); assertTrue(io.one()) // lease acquisition, return still queued
            history.delete(entry.id)
            assertTrue(entry.file.isFile)
            task.cancel(); drain(main, io)
            assertTrue(task.isCancelled)
            assertFalse(entry.file.exists())
            assertFalse(history.hasLeases(entry.id))
            val fresh = journal.enqueue(request("fresh", source))
            assertEquals(fresh.id, journal.claim()?.id)
            assertTrue(uncaught.isEmpty())
        } finally { scope.cancel(); drain(main, io); journal.close() }
    }

    @Test fun `cancelling dispatched work quiesces worker and preserves cancellation despite close failure`() {
        val main = QueueDispatcher(); val io = QueueDispatcher()
        val uncaught = mutableListOf<Throwable>()
        val cleanupErrors = mutableListOf<Exception>()
        val scope = CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        val journal = ArchivalJobStore(temp.newFolder())
        val source = temp.newFile().apply { writeText("input") }
        val old = journal.enqueue(request("old", source))
        var workerAlive = false
        var stopped = 0
        var closed = 0
        var cancellation: Throwable? = null
        var propagated: CancellationException? = null
        val task = scope.launch {
            try {
                ArchivalExecution(journal, io).runNext({ assertTrue(workerAlive); workerAlive = false; stopped++ }, {}, cleanupErrors::add) { claim ->
                    claim.acquireLease { Closeable { closed++; throw IOException("Injected close failure") } }
                    claim.dispatched = true; workerAlive = true
                    awaitCancellation()
                }
            } catch (cancelled: CancellationException) { propagated = cancelled; throw cancelled }
        }
        task.invokeOnCompletion { cancellation = it }
        try {
            drain(main, io); assertTrue(workerAlive)
            journal.pauseExecuting("Service timeout")
            task.cancel(); drain(main, io)
            assertFalse(workerAlive)
            assertEquals(1, stopped); assertEquals(1, closed)
            assertTrue(cancellation is CancellationException)
            assertEquals(1, cleanupErrors.size)
            // Coroutine stack recovery may copy the exception separately from
            // the Job's completion cause; inspect what the ownership helper threw.
            assertTrue(propagated!!.suppressed.any { it is IOException })
            assertEquals(ArchivalState.PAUSED, journal.job(old.id)?.state)
            val fresh = journal.enqueue(request("fresh", source))
            assertEquals(fresh.id, journal.claim()?.id)
            assertTrue(uncaught.isEmpty())
        } finally { scope.cancel(); drain(main, io); journal.close() }
    }

    @Test fun `release survives metadata failure and stale generation cannot clear a later claim`() {
        val root = temp.newFolder()
        val journal = ArchivalJobStore(root)
        val source = temp.newFile().apply { writeText("input") }
        journal.enqueue(request("old", source)); val old = checkNotNull(journal.claim())
        val backup = File(temp.root, "journal-backup")
        check(root.renameTo(backup)); root.writeText("Injected unavailable metadata directory")
        try {
            assertThrows(Exception::class.java) { journal.pause(old.id, old.generation, "pause") }
            assertThrows(Exception::class.java) { journal.releaseExecution(old.id, old.generation) }
        } finally { check(root.delete()); check(backup.renameTo(root)) }
        assertEquals(ArchivalState.PAUSED, journal.job(old.id)?.state)
        journal.retry(old.id)
        val retry = checkNotNull(journal.claim())
        assertEquals(old.id, retry.id)
        assertTrue(retry.generation > old.generation)
        journal.notCreated(old.id, old.generation)
        journal.pause(old.id, old.generation, "late old cleanup")
        journal.releaseExecution(old.id, old.generation)
        journal.enqueue(request("other", source))
        assertNull("Old cleanup released the newer reservation", journal.claim())
        assertEquals(ArchivalState.RUNNING, journal.job(retry.id)?.state)
        journal.close()
    }

    @Test fun `cancellation propagates and secondary failure reporting never reaches the app handler`() {
        val main = QueueDispatcher(); val io = QueueDispatcher()
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + main + CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        val reported = mutableListOf<Exception>()
        val boundary = ArchivalFailureBoundary { reported.add(it); throw IOException("Reporter teardown failed") }
        var cancellation: Throwable? = null
        val cancelled = scope.launch { boundary.run { withContext(io) { throw CancellationException("Cancelled by owner") } } }
        cancelled.invokeOnCompletion { cancellation = it }
        try {
            drain(main, io)
            assertTrue(cancellation is CancellationException)
            assertFalse(boundary.paused)
            assertTrue(reported.isEmpty())
            scope.launch { boundary.run { withContext(io) { throw IOException("Injected initialize/refresh/drain-start failure") } } }
            drain(main, io)
            assertTrue(boundary.paused)
            assertEquals(1, reported.size)
            assertEquals("Reporter teardown failed", reported.single().suppressed.single().message)
            assertTrue(uncaught.isEmpty())
        } finally { scope.cancel(); drain(main, io) }
    }

    @Test fun `old cancelled handoff cannot undo an explicit queued retry of that job`() {
        val main = QueueDispatcher(); val io = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val journal = ArchivalJobStore(temp.newFolder())
        val queued = journal.enqueue(request("owner", temp.newFile().apply { writeText("input") }))
        val task = scope.launch {
            ArchivalExecution(journal, io).runNext({}, {}, { throw it }) { fail("Old handoff was cancelled") }
        }
        try {
            main.one(); io.one()
            val old = checkNotNull(journal.job(queued.id))
            journal.pauseExecuting("Service timeout")
            journal.retry(old.id) // User retry while the old continuation is still queued.
            task.cancel(); drain(main, io)
            assertEquals(ArchivalState.QUEUED, journal.job(old.id)?.state)
            val retry = checkNotNull(journal.claim())
            assertEquals(old.id, retry.id)
            assertTrue(retry.generation > old.generation)
        } finally { scope.cancel(); drain(main, io); journal.close() }
    }
}
