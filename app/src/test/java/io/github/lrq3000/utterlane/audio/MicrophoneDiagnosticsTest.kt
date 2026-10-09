package io.github.lrq3000.utterlane.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneDiagnosticsTest {
    @Test fun closedOrPreviousSessionsCannotOverwriteCurrentEvidence() {
        val diagnostics = MicrophoneDiagnostics()
        val old = diagnostics.begin(MicrophoneOptions.STANDARD)
        old.route("Old route")
        val current = diagnostics.begin(MicrophoneOptions.HFP)
        current.route("HFP ready")
        old.route("Late old callback"); old.finish("Old failure")
        assertTrue(diagnostics.state.value.active)
        assertEquals("HFP ready", diagnostics.state.value.route)
        current.finish()
        current.route("Late current callback")
        assertFalse(diagnostics.state.value.active)
        assertEquals("HFP ready", diagnostics.state.value.route)
    }

    @Test fun reportRedactsDeviceAddressesAndDescribesNonDestructiveGain() {
        val diagnostics = MicrophoneDiagnostics()
        val current = diagnostics.begin(MicrophoneOptions.HFP)
        current.route("Headset 01:23:45:67:89:AB")
        current.input(CaptureInputState(actual = AudioInput("private-key", "01-23-45-67-89-ab", true, 7)))
        val text = diagnostics.state.value.text()
        assertFalse(text.contains("01:23"))
        assertFalse(text.contains("01-23"))
        assertFalse(text.contains("private-key"))
        assertTrue(text.contains("original PCM is preserved"))
    }
}
