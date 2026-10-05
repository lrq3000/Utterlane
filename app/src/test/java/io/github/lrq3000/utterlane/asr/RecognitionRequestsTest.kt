package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class RecognitionRequestsTest {
    @Test fun statusAndProgressLeaveFinalReplyRegistered() {
        val requests = RecognitionRequests<String>()
        val pending = requests.register(1, ProgressWatchdog(1, 1000, 0, 0, false))
        requests.progress(RecognitionProgress(1, 1, "executing", 0), 100, false)
        requests.progress(RecognitionProgress(1, 2, "encoder", 1, true, false), 500, false)
        assertFalse(pending.result.isDone)
        assertTrue(requests.complete(1, "final"))
        assertEquals("final", pending.result.get())
        assertFalse(requests.complete(1, "duplicate"))
    }

    @Test fun cancelledAndUnknownRequestsCannotLeakIntoTheirReplacement() {
        val requests = RecognitionRequests<String>()
        requests.register(1, ProgressWatchdog(1, 1000, 0, 0, false))
        requests.remove(1)
        val replacement = requests.register(2, ProgressWatchdog(2, 1000, 0, 0, false))
        assertFalse(requests.progress(RecognitionProgress(1, 50, "encoder", 50, true), 900, false))
        assertFalse(requests.complete(1, "late"))
        assertFalse(replacement.result.isDone)
        assertEquals(ProgressWatchdog.Expiration.UNAVAILABLE_OPAQUE, replacement.watchdog.expiration(1000, false))
    }

    @Test fun workerDeathFailsAllPendingWaiters() {
        val requests = RecognitionRequests<String>()
        val pending = (1..3).map { requests.register(it, ProgressWatchdog(it, 0, 0, 0, false)) }
        requests.fail(IllegalStateException("worker exited"))
        assertTrue(pending.all { it.result.isCompletedExceptionally })
    }
}
