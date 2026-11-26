package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionCallQueueTest {
    @Test(timeout = 5000) fun livePreparationWinsAtTheNextBoundaryAndBothInputsKeepTheirTurns(): Unit = runBlocking {
        val queue = RecognitionCallQueue()
        val microphone = CaptureAdmission.Lease {}
        val preparation = RecognitionSessionPreparation(queue) { RuntimeOptions() }
        val events = mutableListOf<String>()
        val releaseFile = CompletableDeferred<Unit>()
        val releaseLive = CompletableDeferred<Unit>()
        val enteredLive = CompletableDeferred<Unit>()
        val file = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withTurn { events += "file window"; releaseFile.await(); events += "file window done" }
        }
        val fileEof = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withTurn { events += "file speaker EOF" }
        }
        val live = launch(start = CoroutineStart.UNDISPATCHED) {
            preparation.prepare(null, microphone) { events += "live preparation"; enteredLive.complete(Unit); releaseLive.await() }
            queue.withTurn(microphone) { events += "live window" }
        }
        assertEquals(listOf("file window"), events)
        releaseFile.complete(Unit)
        file.join()
        // The old file is still queued when the live call enters; it cannot overlap live preparation.
        enteredLive.await()
        assertEquals(listOf("file window", "file window done", "live preparation"), events)
        releaseLive.complete(Unit)
        live.join(); fileEof.join()
        // The second live call was not ready at handoff; that slot belonged to the file.
        assertEquals(listOf("file window", "file window done", "live preparation", "file speaker EOF", "live window"), events)
    }

    @Test(timeout = 5000) fun stopDemotesAnAlreadyWaitingCallWithoutLosingItsOriginalPlace(): Unit = runBlocking {
        val queue = RecognitionCallQueue()
        val oldMic = CaptureAdmission.Lease {}
        val current = CaptureAdmission.Lease {}
        val events = mutableListOf<String>()
        coroutineScope {
            queue.withTurn {
                launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn { events += "first file" } }
                launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn(oldMic) { events += "old mic EOF" } }
                launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn { events += "second file" } }
                oldMic.stopped()
                launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn(current) { events += "new mic" } }
            }
        }
        assertEquals(listOf("new mic", "first file", "old mic EOF", "second file"), events)
    }

    @Test(timeout = 5000) fun cancellationWhileQueuedOrAfterGrantCannotStealOrStrandATurn(): Unit = runBlocking {
        val queue = RecognitionCallQueue()
        val events = mutableListOf<String>()
        val microphone = CaptureAdmission.Lease {}
        lateinit var granted: Job
        queue.withTurn {
            launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn { events += "file" } }
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn(microphone) { events += "cancelled" } }
            cancelled.cancelAndJoin()
            granted = launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn(microphone) { events += "cancelled after grant" } }
            assertEquals(emptyList<String>(), events)
        }
        // On this single dispatcher the selected waiter has not resumed yet.
        granted.cancelAndJoin()
        queue.withTurn { events += "later" }
        assertEquals(listOf("file", "later"), events)
    }
}
