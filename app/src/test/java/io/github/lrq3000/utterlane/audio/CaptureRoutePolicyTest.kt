package io.github.lrq3000.utterlane.audio

import org.junit.Assert.*
import org.junit.Test

class CaptureRoutePolicyTest {
    private val phone = AudioInput(AudioInput.PHONE_KEY, "Phone", false, 1)
    private val headset = AudioInput("headset", "Headset", true, 7)
    private var now = 0L

    @Test fun newConnectionsDoNotChangeTheFrozenPhoneTarget() {
        val policy = CaptureRoutePolicy(phone) { now }
        policy.observe(phone, targetAvailable = true, frames = true)
        assertEquals(AudioInput.PHONE_KEY, policy.desiredKey)
        assertFalse(policy.state.connecting)
        assertNull(policy.state.fallbackFrom)
    }

    @Test fun initialNegotiationKeepsRealPhoneCaptionUntilHeadsetAudioArrives() {
        val policy = CaptureRoutePolicy(headset) { now }
        policy.observe(phone, true, true)
        assertTrue(policy.state.connecting)
        assertTrue(policy.state.actual!!.isPhone)
        policy.observe(headset, true, true)
        assertFalse(policy.state.connecting)
        assertEquals(headset, policy.state.actual)
        assertNull(policy.state.fallbackFrom)
    }

    @Test fun lossFallsBackAndReconnectionDoesNotUndoIt() {
        val policy = CaptureRoutePolicy(headset) { now }
        policy.observe(headset, true, true)
        policy.observe(null, false, false)
        assertEquals(AudioInput.PHONE_KEY, policy.desiredKey)
        assertEquals(InputFallbackReason.DISCONNECTED, policy.state.fallbackReason)
        assertFalse(policy.state.receivingFallback)
        policy.observe(phone, true, true)
        assertTrue(policy.state.receivingFallback)
        policy.recorderReopened()
        policy.observe(phone, true, true)
        assertEquals(AudioInput.PHONE_KEY, policy.desiredKey)
        assertEquals(headset, policy.state.fallbackFrom)
    }

    @Test fun disconnectedBeforeStartAndRejectedRequestFallBackWithoutWaiting() {
        val missing = CaptureRoutePolicy(headset) { now }
        missing.observe(phone, false, true)
        assertTrue(missing.state.receivingFallback)
        val rejected = CaptureRoutePolicy(headset) { now }
        rejected.fallback(InputFallbackReason.UNAVAILABLE)
        assertEquals(AudioInput.PHONE_KEY, rejected.desiredKey)
    }

    @Test fun routeRequestAcceptanceCannotMasqueradeAsHeadsetCapture() {
        val policy = CaptureRoutePolicy(headset) { now }
        policy.observe(phone, true, true)
        now = 5000
        policy.observe(phone, true, true)
        assertEquals(InputFallbackReason.UNAVAILABLE, policy.state.fallbackReason)
        assertTrue(policy.state.receivingFallback)
        assertTrue(policy.state.actual!!.isPhone)
    }

    @Test fun fallbackConfirmationRequiresPhoneRouteAndUnsilencedFrames() {
        val policy = CaptureRoutePolicy(headset) { now }
        policy.fallback(InputFallbackReason.DISCONNECTED)
        policy.observe(headset, false, true)
        assertFalse(policy.state.receivingFallback)
        policy.observe(phone, false, false)
        assertFalse(policy.state.receivingFallback)
        policy.observe(phone, false, true, silenced = true)
        assertFalse(policy.state.receivingFallback)
        policy.observe(phone, false, true)
        assertTrue(policy.state.receivingFallback)
    }

    @Test fun lostRouteOrStalledExternalCaptureAlsoTriggersPhoneFallback() {
        val changed = CaptureRoutePolicy(headset) { now }
        changed.observe(headset, true, true)
        changed.observe(phone, true, true)
        assertEquals(InputFallbackReason.ROUTE_CHANGED, changed.state.fallbackReason)
        val stalled = CaptureRoutePolicy(headset) { now }
        stalled.observe(headset, true, true)
        now = 1600
        stalled.observe(headset, true, false)
        assertEquals(InputFallbackReason.UNAVAILABLE, stalled.state.fallbackReason)
    }

    @Test fun fallbackRecoveryHasOneReopenAndFiniteFailureDeadline() {
        val policy = CaptureRoutePolicy(headset) { now }
        policy.fallback(InputFallbackReason.DISCONNECTED)
        now = 1500
        assertTrue(policy.takeReopenRequest())
        assertFalse(policy.takeReopenRequest())
        policy.recorderReopened()
        now = 5000
        assertTrue(policy.failed)
    }

    @Test fun healthyPhoneFallbackDoesNotExpireAndNewInterruptionGetsItsOwnBudget() {
        val policy = CaptureRoutePolicy(headset) { now }
        policy.fallback(InputFallbackReason.DISCONNECTED)
        for (time in 0L..10000L step 1000) {
            now = time
            policy.observe(phone, false, true)
            assertFalse(policy.failed)
        }
        policy.recorderReopened()
        assertFalse(policy.state.receivingFallback)
        assertFalse(policy.failed)
        now += 5000
        assertTrue(policy.failed)
    }
}
