package io.github.lrq3000.utterlane.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class WorkQuitTest {
    @Test fun appTasksCloseOnlyAfterEveryOwnerHasCheckpointed() = runBlocking {
        val mic = CompletableDeferred<Unit>()
        val archive = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val work = async {
            quitUtterlaneWork(
                stopCapture = { mic.await(); events += "capture" },
                pauseFiles = { events += "files" },
                pauseArchives = { archive.await(); events += "archive" },
                awaitLiveRows = { assertTrue(events.containsAll(listOf("capture", "files", "archive"))); events += "live rows" },
                close = { events += "close" }
            )
        }
        yield()
        mic.complete(Unit)
        yield()
        assertFalse("Archive checkpoint is still pending", "close" in events)
        archive.complete(Unit)
        work.await()
        assertEquals(listOf("live rows", "close"), events.takeLast(2))
    }

    @Test fun failedCheckpointDoesNotCloseTheApp() = runBlocking {
        var closed = false
        try {
            quitUtterlaneWork({}, {}, { error("Archive storage failed") }, {}, { closed = true })
            fail("Failure should reach the caller")
        } catch (error: IllegalStateException) {
            assertEquals("Archive storage failed", error.message)
        }
        assertFalse(closed)
    }
}
