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
        val backendEnded = AtomicBoolean()
        val lease = NativeSessionLease { backendEnded.set(true) }
        val events = mutableListOf<String>()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                queue.withTurn {
                    awaitNativeReply { lease.useBackend {
                        dispatched.complete(Unit)
                        reply.join()
                        interrupted.set(Thread.interrupted())
                    } }
                    events += "cancelled result"
                }
            } finally { lease.close() }
        }
        try {
            dispatched.await()
            launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn { events += "next file" } }
            assertTrue("Delete/Back must finish before a hung native reply", withTimeoutOrNull(1000) { caller.cancelAndJoin(); true } == true)
            launch(start = CoroutineStart.UNDISPATCHED) { queue.withTurn(CaptureAdmission.Lease {}) { events += "live" } }
            yield()
            assertFalse(reply.isDone)
            assertFalse("END_SESSION must not overtake its native inference", backendEnded.get())
            assertTrue(events.isEmpty())
        } finally { reply.complete(Unit) }
        caller.join()
        queue.withTurn { events += "done" }
        assertFalse("Cancelling a client wait would abandon the native turn before its reply", interrupted.get())
        assertTrue(backendEnded.get())
        assertEquals(listOf("live", "next file", "done"), events)
    }

    @Test(timeout = 5000) fun cancelledPreparationFinishesOnlyItsModelAndNeverConstructsAnUnreturnedSession(): Unit = runBlocking {
        val queue = RecognitionCallQueue()
        val preparation = RecognitionSessionPreparation(queue) { RuntimeOptions() }
        val started = CompletableDeferred<Unit>()
        val reply = CompletableFuture<Unit>()
        val prepared = AtomicBoolean()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            preparation.prepare(null) {
                awaitNativeReply { started.complete(Unit); reply.join(); prepared.set(true) }
                fail("Cancelled owner constructed a session")
            }
        }
        try {
            started.await()
            assertTrue(withTimeoutOrNull(1000) { caller.cancelAndJoin(); true } == true)
            val next = async(start = CoroutineStart.UNDISPATCHED) { queue.withTurn { prepared.get() } }
            assertFalse("Preparation must retain its turn until readiness is published", next.isCompleted)
            reply.complete(Unit)
            assertTrue(next.await())
        } finally { reply.complete(Unit) }
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
                    awaitNativeReply {
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
        // Coroutine debug stack recovery and Future.get may each wrap the worker failure. The observable contract is propagation of the original reset and release of the turn.
        val failure = requireNotNull(waiting.await().exceptionOrNull())
        val cause = generateSequence(failure) { it.cause }.last()
        assertTrue(cause is IllegalStateException)
        assertEquals("worker reset", cause.message)
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
