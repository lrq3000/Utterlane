package io.github.lrq3000.utterlane.home

import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HomeServiceIdleStopTest {
    @Test fun importToAutomaticTranscriptionInTheSameMainTurnDoesNotStopTheService() = Fixture().use { f ->
        f.observeIdle()
        // The existing model sets importing=false, then starts automatic
        // transcription. Immediate observation must not split this same-turn handoff.
        f.busy = true
        f.drain()
        assertEquals(0, f.stops)
    }

    @Test fun genuineIdleCompletionStillStopsOnTheNextMainTurn() = Fixture().use { f ->
        f.observeIdle()
        assertEquals(0, f.stops)
        f.drain()
        assertEquals(1, f.stops)
    }

    @Test fun aNewServiceRequestInvalidatesAnOlderQueuedStop() = Fixture().use { f ->
        f.observeIdle()
        f.ownsToken = false
        f.drain()
        assertEquals(0, f.stops)
    }

    @Test fun destructionCancelsTheQueuedIdleCheck() = Fixture().use { f ->
        f.observeIdle()
        f.root.cancel()
        f.drain()
        assertEquals(0, f.stops)
    }

    private class Fixture : Closeable {
        var busy = false
        var ownsToken = true
        var stops = 0
        val root = SupervisorJob()
        private val dispatcher = QueuedDispatcher()
        private val scope = CoroutineScope(root + dispatcher)
        fun observeIdle() = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            HomeServiceIdleStop.recheck(isIdle = { ownsToken && !busy }, onIdle = { stops++ })
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
