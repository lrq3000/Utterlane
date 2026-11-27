package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class CaptureAdmissionTest {
    @Test fun quitFencesNewRecordingsAndIncludesAnOldTailAndTheCurrentMic() {
        val admission = CaptureAdmission()
        val tail = CaptureAdmission.Lease {}
        val live = CaptureAdmission.Lease {}
        val next = CaptureAdmission.Lease {}
        assertTrue(admission.acquire(tail))
        admission.releaseHardware(tail) {}
        assertTrue(admission.acquire(live))
        assertEquals(setOf(tail, live), admission.beginQuit().toSet())
        admission.releaseHardware(live) {}
        assertFalse(admission.acquire(next))
        admission.endQuit()
        assertTrue(admission.acquire(next))
    }

    @Test fun stopDropsPriorityButOnlyHardwareCloseAdmitsTheNextCapture() {
        val admission = CaptureAdmission()
        val old = CaptureAdmission.Lease {}
        val next = CaptureAdmission.Lease {}
        assertTrue(admission.acquire(old))
        old.stopped()
        assertFalse(old.isLive)
        assertFalse(admission.acquire(next))
        admission.releaseHardware(old) {}
        assertFalse(admission.isBusy())
        assertTrue(admission.hasSessions())
        assertTrue(admission.acquire(next))
        admission.releaseHardware(old) { fail("Stale callback cannot touch hardware") }
        admission.finish(old)
        assertTrue(admission.isBusy())
        assertTrue(next.isLive)
        assertFalse(admission.acquire(CaptureAdmission.Lease {}))
        admission.releaseHardware(next) {}
        admission.finish(next)
        assertFalse(admission.hasSessions())
        assertFalse(admission.isBusy())
    }

    @Test fun resetIncludesDrainingAndCapturingOwnersButNeverFinishedOnes() {
        val admission = CaptureAdmission()
        val reset = mutableListOf<String>()
        val first = CaptureAdmission.Lease { reset += "finished" }
        val draining = CaptureAdmission.Lease { reset += "draining" }
        val current = CaptureAdmission.Lease { reset += "current" }
        assertTrue(admission.acquire(first))
        admission.releaseHardware(first) {}
        admission.finish(first)
        assertTrue(admission.acquire(draining))
        admission.releaseHardware(draining) {}
        assertFalse(draining.isLive)
        assertTrue(admission.acquire(current))
        val toReset = admission.resetTargets()
        admission.releaseHardware(current) {}
        assertTrue(admission.acquire(CaptureAdmission.Lease { reset += "after reset" }))
        toReset.forEach { it.reset() }
        assertEquals(listOf("current", "draining"), reset.sorted())
        assertTrue(admission.isBusy())
    }
}
