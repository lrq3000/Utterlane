package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.asr.TranscriptStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

class TranscriptCopyOperationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun copySurvivesDisposalBeforeAnyDispatcherRuns() = fixture().use { copy ->
        val text = "Offscreen prefix " + "spoken words ".repeat(1000) + "visible ending"
        copy.store.append(text)
        assertFalse(copy.store.preview().contains("Offscreen prefix"))
        val job = copy.start()
        copy.store.dispose()
        copy.drain()
        assertEquals("Disposal before file open must not cause an uncaught IO error", emptyList<Throwable>(), copy.uncaught)
        assertTrue(copy.failures.isEmpty())
        assertEquals(listOf(text), copy.results)
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertFalse("The last reader must honor the pending deletion", copy.store.file.exists())
    }

    @Test fun cancellingBeforeIoStartsReleasesTheLeaseWithoutDelivery() = fixture().use { copy ->
        copy.store.append("keep until the reader releases")
        val job = copy.start()
        copy.store.dispose()
        assertTrue("The click must acquire its lease synchronously", copy.store.file.exists())
        job.cancel(CancellationException("Cancelled before IO"))
        copy.drain()
        assertTrue(job.isCompleted && job.isCancelled)
        copy.assertNoDelivery()
        assertFalse(copy.store.file.exists())
    }

    @Test fun alreadyCancelledScopeStillReleasesItsSynchronouslyAcquiredLease() = fixture().use { copy ->
        copy.root.cancel()
        val job = copy.start()
        copy.store.dispose()
        assertTrue("Even a cancelled scope must enter the lease cleanup path", copy.store.file.exists())
        copy.drain()
        assertTrue(job.isCompleted && job.isCancelled)
        copy.assertNoDelivery()
        assertFalse(copy.store.file.exists())
    }

    @Test fun cancellingAfterReadBeforeDeliveryStillReleasesTheLease() = fixture().use { copy ->
        copy.store.append("ready to copy")
        val job = copy.start()
        copy.store.dispose()
        copy.io.runNext()
        assertTrue("Clipboard delivery belongs to the UI dispatcher", copy.results.isEmpty())
        assertTrue(copy.store.file.exists())
        job.cancel()
        copy.drain()
        assertTrue(job.isCompleted && job.isCancelled)
        copy.assertNoDelivery()
        assertFalse(copy.store.file.exists())
    }

    @Test fun realIoFailureIsReportedAndReleasesThePendingDeletion() = fixture().use { copy ->
        val job = copy.start()
        // A lease protects normal disposal, not external filesystem failures.
        // Replace the file with a directory to fail the real read without mocks.
        assertTrue(copy.store.file.delete())
        assertTrue(copy.store.file.mkdir())
        File(copy.store.file, "marker").writeText("remove after the reader releases")
        copy.store.dispose()
        copy.drain()
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertTrue(copy.results.isEmpty())
        assertTrue(copy.failures.single() is IOException)
        assertTrue(copy.uncaught.isEmpty())
        assertFalse("An error must not leak the reader lease", copy.store.file.exists())
    }

    @Test fun oversizedUtf8TextReturnsTheExportSignalAndReleasesTheLease() = fixture().use { copy ->
        copy.store.append("🙂".repeat(TranscriptStore.TRANSFER_LIMIT / 4 + 1))
        val job = copy.start()
        copy.store.dispose()
        copy.drain()
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(listOf<String?>(null), copy.results)
        assertTrue(copy.failures.isEmpty())
        assertTrue(copy.uncaught.isEmpty())
        assertFalse(copy.store.file.exists())
    }

    private fun fixture() = CopyFixture(TranscriptStore(directory.newFile()))

    /** Separate queues make the click -> IO -> delivery race deterministic, without sleeps. */
    private class CopyFixture(val store: TranscriptStore) : Closeable {
        val io = QueuedDispatcher()
        private val ui = QueuedDispatcher()
        val root = SupervisorJob()
        val results = mutableListOf<String?>()
        val failures = mutableListOf<Exception>()
        val uncaught = mutableListOf<Throwable>()
        private val scope = CoroutineScope(root + ui + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        private val operation = TranscriptCopyOperation(scope, io)

        fun start() = operation.start(store, { results += it }, { failures += it })

        fun drain() {
            while (io.hasTasks || ui.hasTasks) {
                if (io.hasTasks) io.runNext()
                if (ui.hasTasks) ui.runNext()
            }
        }

        fun assertNoDelivery() {
            assertTrue(results.isEmpty())
            assertTrue("Cancellation must not be reported as an IO failure", failures.isEmpty())
            assertTrue(uncaught.isEmpty())
        }

        override fun close() {
            root.cancel()
            drain()
            store.dispose()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val hasTasks get() = tasks.isNotEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { tasks.removeFirst().run() }
    }
}
