package io.github.lrq3000.utterlane.home

import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogState
import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class HomeRetryHandoffTest {
    @Test fun immediateServiceObserverNeverSeesIdleBeforeQueuedRunningUpdate() = Fixture().use { f ->
        f.retry { f.model.value = TranscriptionDialogState(running = true, preview = "previous text") }
        assertTrue("The service must see running even before the app collector executes", f.home.value.result.running)
        assertFalse(f.home.value.preparing)
        assertTrue("Transient idle can stop the FGS before retry starts", f.observed.all { it.busy })
        f.drain()
        assertTrue(f.observed.all { it.busy })
        assertEquals("previous text", f.home.value.result.preview)
    }

    @Test fun successfulCompletionReleasesForegroundOwnership() = finishesWith(TranscriptionDialogState(preview = "finished text"))

    @Test fun failedRetryReleasesForegroundOwnershipAndRetainsItsError() = finishesWith(
        TranscriptionDialogState(message = "Model unavailable", preview = "usable earlier text"))

    private fun finishesWith(result: TranscriptionDialogState) = Fixture().use { f ->
        f.retry { f.model.value = TranscriptionDialogState(running = true) }
        f.drain()
        assertTrue(f.home.value.busy)
        f.model.value = result
        f.drain()
        assertFalse(f.home.value.busy)
        assertFalse(f.home.value.preparing)
        assertEquals(result, f.home.value.result)
    }

    @Test fun retryWithNoSourceOrNoOwnerDoesNotLeavePreparationStuck() = Fixture().use { f ->
        f.retry { /* The model can return without starting when its source is absent. */ }
        assertFalse(f.home.value.busy)
        assertFalse(f.home.value.preparing)
        f.drain()
        assertFalse(f.home.value.busy)
    }

    @Test fun refusedRetryStillAdoptsAnotherAlreadyActiveModelOperation() = Fixture().use { f ->
        // The model's saving guard refuses Retry while Home's observer is behind.
        f.model.value = TranscriptionDialogState(saving = true)
        f.retry { }
        assertTrue(f.home.value.result.saving)
        assertTrue(f.observed.all { it.busy })
        f.model.value = TranscriptionDialogState()
        f.drain()
        assertFalse(f.home.value.busy)
    }

    @Test fun synchronousFailureCannotLeavePreparationStuckOrHideModelState() = Fixture().use { f ->
        assertThrows(IllegalStateException::class.java) {
            f.retry {
                f.model.value = TranscriptionDialogState(message = "Start failed")
                error("Start failed")
            }
        }
        assertFalse(f.home.value.preparing)
        assertFalse(f.home.value.busy)
        assertEquals("Start failed", f.home.value.result.message)
    }

    @Test fun retryThatCompletesSynchronouslyIsAllowedToBecomeIdle() = Fixture().use { f ->
        f.retry {
            f.model.value = TranscriptionDialogState(running = true)
            f.model.value = TranscriptionDialogState(preview = "quick result")
        }
        assertFalse(f.home.value.busy)
        assertEquals("quick result", f.home.value.result.preview)
    }

    /** Same ordering as Main (app collector) versus Main.immediate (service),
     * without Android, timing sleeps or a simulated final-state-only assertion. */
    private class Fixture : Closeable {
        val home = MutableStateFlow(HomeState(preparing = true))
        val model = MutableStateFlow(TranscriptionDialogState())
        val observed = mutableListOf<HomeState>()
        private val root = SupervisorJob()
        private val dispatcher = QueuedDispatcher()
        init {
            CoroutineScope(root + dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
                model.collect { result -> home.update { it.copy(result = result) } }
            }
            CoroutineScope(root + Dispatchers.Unconfined).launch { home.collect { observed += it } }
        }
        fun retry(action: () -> Unit) = HomeRetryHandoff.run(home, action) { model.value }
        fun drain() = dispatcher.drain()
        override fun close() { root.cancel(); drain() }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
