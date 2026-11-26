package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class CaptureAdmissionTest {
    @Test fun stopDropsPriorityButOnlyHardwareCloseAdmitsTheNextCapture() {
        val admission = CaptureAdmission()
        val old = CaptureAdmission.Lease {}
        val next = CaptureAdmission.Lease {}
        assertTrue(admission.acquire(old))
        old.stopped()
        assertFalse(old.isLive)
        assertFalse(admission.acquire(next))
        admission.hardwareClosed(old)
        assertFalse(admission.isBusy())
        assertTrue(admission.hasSessions())
        assertTrue(admission.acquire(next))
        admission.hardwareClosed(old)
        admission.finish(old)
        assertTrue(admission.isBusy())
        assertTrue(next.isLive)
        assertFalse(admission.acquire(CaptureAdmission.Lease {}))
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
        admission.finish(first)
        assertTrue(admission.acquire(draining))
        admission.hardwareClosed(draining)
        assertFalse(draining.isLive)
        assertTrue(admission.acquire(current))
        val toReset = admission.resetTargets()
        admission.hardwareClosed(current)
        assertTrue(admission.acquire(CaptureAdmission.Lease { reset += "after reset" }))
        toReset.forEach { it.reset() }
        assertEquals(listOf("current", "draining"), reset.sorted())
        assertTrue(admission.isBusy())
    }
}
