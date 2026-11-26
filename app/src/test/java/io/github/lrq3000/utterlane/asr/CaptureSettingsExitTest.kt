package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class CaptureSettingsExitTest {
    @Test fun eofWinsEvenIfTheUiIsStillShowingItsRecordingWarning() {
        val gate = CaptureSettingsExit()
        gate.captureClosing()
        assertFalse(gate.confirmDiscard { fail("Capture already ended; keep its audio") })
    }

    @Test fun explicitDiscardWinsAtMostOnceBeforeTheMicrophoneCloses() {
        val gate = CaptureSettingsExit()
        var requested = 0
        assertTrue(gate.confirmDiscard { requested++ })
        gate.captureClosing()
        assertFalse(gate.confirmDiscard { requested++ })
        assertEquals(1, requested)
    }
}
