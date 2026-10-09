package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogState
import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class HomeResultObserverTest {
    @Test fun ownerChangedBetweenEntryCheckAndStateUpdateCannotOverwritePromotedResult() {
        val root = SupervisorJob()
        val home = MutableStateFlow(HomeState(result = TranscriptionDialogState(preview = "promoted result")))
        val obsolete = MutableStateFlow(TranscriptionDialogState(running = true, preview = "obsolete result"))
        var checks = 0
        var callbacks = 0
        try {
            HomeResultObserver(CoroutineScope(root + Dispatchers.Unconfined), Dispatchers.Unconfined).observe(home, obsolete,
                isCurrent = { ++checks == 1 }, onStarted = { callbacks++ }, onResult = { _, _ -> callbacks++ })
            assertEquals("promoted result", home.value.result.preview)
            assertEquals(0, callbacks)
        } finally { root.cancel() }
    }

    @Test fun initialHydrationDoesNotEraseAnActualCaptureFailureOrPermissionError() = Fixture().use { f ->
        f.model.value = TranscriptionDialogState(importing = true)
        f.drain()
        f.model.value = TranscriptionDialogState()
        f.drain()
        assertEquals("Capture failed", f.home.value.message)
        assertTrue(f.home.value.permissionDenied)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun detailRetryClearsOldErrorsAndRequestsForegroundOwnershipBeforeReturning() = Fixture().use { f ->
        f.model.value = TranscriptionDialogState(running = true)
        // Do NOT drain the queued app dispatcher. A direct dialog click must
        // establish the request while still in its originating UI callback.
        assertNull(f.home.value.message)
        assertFalse(f.home.value.permissionDenied)
        assertTrue(f.home.value.busy)
        assertEquals(1, f.requests.size)
        assertTrue(f.connection.pending)
        f.model.value = TranscriptionDialogState(preview = "successful retry")
        f.drain()
        assertNull(f.home.value.message)
        assertEquals("successful retry", f.home.value.result.preview)
        assertFalse(f.home.value.busy)
    }

    @Test fun aFailedDetailRetryShowsTheNewFailureWithoutTheOldPermissionGate() = Fixture().use { f ->
        f.model.value = TranscriptionDialogState(running = true)
        f.drain()
        f.model.value = TranscriptionDialogState(message = "Retry model failure")
        f.drain()
        assertNull(f.home.value.message)
        assertFalse(f.home.value.permissionDenied)
        assertEquals("Retry model failure", f.home.value.result.message)
    }

    @Test fun progressDoesNotClearANewErrorOrRepeatedlyRequestAFailedService() = Fixture().use { f ->
        f.model.value = TranscriptionDialogState(running = true)
        f.drain()
        f.connection.detached(f.requests.single())
        f.home.update { it.copy(message = "Foreground start denied") }
        f.model.value = f.model.value.copy(progress = 12)
        f.drain()
        assertEquals("Foreground start denied", f.home.value.message)
        assertEquals(1, f.requests.size)
    }

    @Test fun anAttachedServiceAlreadyOwnsADirectRetry() = Fixture().use { f ->
        f.connection.request("existing"); f.connection.started("existing")
        f.model.value = TranscriptionDialogState(running = true)
        f.drain()
        assertTrue(f.connection.attached)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun detailRetryDuringStopSelfWindowRequestsNewOwnershipBeforeOldDestruction() = Fixture().use { f ->
        f.connection.request("old"); f.connection.started("old")
        f.connection.stopping("old")
        f.model.value = TranscriptionDialogState(running = true)
        f.drain()
        assertEquals("A stopping service cannot own this new retry", 1, f.requests.size)
        val replacement = f.requests.single()
        f.connection.detached("old")
        assertTrue("Late destruction must not detach the replacement request", f.connection.pending)
        f.connection.started(replacement)
        assertTrue(f.connection.attached)
        assertTrue(f.connection.owns(replacement))
    }

    @Test fun obsoleteResultOwnerCannotClearErrorsOrStartAService() = Fixture().use { f ->
        f.current = false
        f.model.value = TranscriptionDialogState(running = true)
        f.drain()
        assertEquals("Capture failed", f.home.value.message)
        assertTrue(f.home.value.permissionDenied)
        assertTrue(f.requests.isEmpty())
    }

    private class Fixture : Closeable {
        val home = MutableStateFlow(HomeState(message = "Capture failed", permissionDenied = true))
        val model = MutableStateFlow(TranscriptionDialogState())
        val connection = HomeServiceConnection()
        val requests = mutableListOf<String>()
        var current = true
        private val root = SupervisorJob()
        private val dispatcher = QueuedDispatcher()
        init {
            HomeResultObserver(CoroutineScope(root + dispatcher), Dispatchers.Unconfined).observe(home, model,
                isCurrent = { current }, onStarted = {
                    if (connection.needsStart) {
                        val token = "retry-${requests.size}"
                        requests.add(token); connection.request(token)
                    }
                }, onResult = { _, _ -> })
            drain() // Subscribe the old queued implementation before triggering a retry.
        }
        fun drain() = dispatcher.drain()
        override fun close() { root.cancel(); drain() }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
