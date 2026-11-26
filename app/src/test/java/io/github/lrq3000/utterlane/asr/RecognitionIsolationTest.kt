package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class RecognitionIsolationTest {
    @Test(timeout = 5000) fun cancellingAfterDispatchDoesNotInterruptTheNativeReplyWaitOrDeliverItsResult(): Unit = runBlocking {
        val queue = RecognitionCallQueue()
        val dispatched = CompletableDeferred<Unit>()
        val reply = CompletableFuture<Unit>()
        val interrupted = AtomicBoolean()
        val events = mutableListOf<String>()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withTurn {
                queue.awaitNativeReply {
                    dispatched.complete(Unit)
                    // Even a client which ignores interrupt and waits for the worker must not receive one from ordinary caller cancellation.
                    reply.join()
                    interrupted.set(Thread.interrupted())
                }
                events += "cancelled result"
            }
        }
        try {
            dispatched.await()
            caller.cancel()
            launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn { events += "next file" } }
            launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn(CaptureAdmission.Lease {}) { events += "live" } }
            assertTrue(events.isEmpty())
        } finally { reply.complete(Unit) }
        caller.join()
        queue.withTurn { events += "done" }
        assertFalse("Cancelling a client wait would abandon the native turn before its reply", interrupted.get())
        assertEquals(listOf("live", "next file", "done"), events)
    }

    @Test fun finishedSessionCannotRelinquishHardwareAfterFailedRelease() {
        val admission = CaptureAdmission()
        var canClose = false
        val old = CaptureAdmission.Lease {}
        val next = CaptureAdmission.Lease {}
        assertTrue(admission.acquire(old))
        fun close() = admission.releaseHardware(old) { check(canClose) }
        assertTrue(runCatching { close() }.isFailure)
        assertTrue(admission.needsReleaseRetry(old))
        admission.finish(old)
        assertFalse(admission.acquire(next))
        assertTrue(admission.hasSessions())
        assertEquals(listOf(old), admission.resetTargets())
        canClose = true
        close()
        assertFalse(admission.needsReleaseRetry(old))
        assertTrue(admission.acquire(next))
        admission.releaseHardware(old) { fail("Late retry must not touch a new owner") }
        assertTrue(admission.isBusy())
    }

    @Test(timeout = 5000) fun workerResetEndsHungNativeWaitEvenWithBothWatchdogsDisabled(): Unit = runBlocking {
        val requests = RecognitionRequests<String>()
        val queue = RecognitionCallQueue()
        val dispatched = CompletableDeferred<Unit>()
        val waiting = async {
            runCatching {
                queue.withTurn {
                    queue.awaitNativeReply {
                        val pending = requests.register(1, ProgressWatchdog(1, 0, 0, 0, false))
                        dispatched.complete(Unit)
                        pending.result.get()
                    }
                }
            }
        }
        dispatched.await()
        val next = async(start = CoroutineStart.UNDISPATCHED) { queue.withTurn { "replacement" } }
        assertFalse(next.isCompleted)
        requests.fail(IllegalStateException("worker reset"))
        assertEquals("worker reset", waiting.await().exceptionOrNull()?.cause?.message)
        assertEquals("replacement", next.await())
    }

    @Test fun saturatedSpeakerCacheDecodesOwnedSpeechOnceWithoutDisplacingAnySpeaker() {
        val occupied = setOf(1L, 2L, 3L, 4L)
        var decodes = 0
        val result = RecognitionWorkerService.withSpeakerCapacity(5, occupied, { decodes++; "fifth" }) {
            error("No native speaker cache is available")
        }
        assertEquals(listOf(SpeechSpan("fifth", SpeechSpan.UNLABELED)), result)
        assertEquals(1, decodes)
        val labeled = listOf(SpeechSpan("first", 0))
        assertEquals(labeled, RecognitionWorkerService.withSpeakerCapacity(1, occupied, { error("Old speaker cache was abandoned") }) { labeled })
    }

    @Test fun onlyThePreparingOrDecodingOwnerReceivesWorkerProgress(): Unit = runBlocking {
        val ownership = RecognitionActivityOwnership()
        val mic = Any(); val file = Any()
        val microphoneEvents = mutableListOf<String>(); val fileEvents = mutableListOf<String>()
        val a = ownership.observe(mic, this) { if (it.active) microphoneEvents += it.stage }
        val b = ownership.observe(file, this) { if (it.active) fileEvents += it.stage }
        val options = RuntimeOptions()
        try {
            val loading = ownership.begin(options, options, progressOwner = mic)
            ownership.publish(RecognitionActivity(stage = "live load", active = true))
            yield()
            ownership.finish(loading)
            val decoding = ownership.begin(options, options, 2L, file)
            ownership.publish(RecognitionActivity(stage = "file decode", active = true))
            yield()
            ownership.finish(decoding)
            val speaker = ownership.begin(options, options, 3L, mic)
            ownership.publish(RecognitionActivity(stage = "live speaker", active = true))
            yield()
            ownership.finish(speaker)
            assertEquals(listOf("live load", "live speaker"), microphoneEvents)
            assertEquals(listOf("file decode"), fileEvents)
        } finally { a.cancelAndJoin(); b.cancelAndJoin() }
    }
}
