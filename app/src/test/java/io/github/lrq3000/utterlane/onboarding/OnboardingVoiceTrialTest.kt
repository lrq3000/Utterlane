package io.github.lrq3000.utterlane.onboarding

import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.asr.CaptureMetrics
import io.github.lrq3000.utterlane.asr.LiveSpeakerControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OnboardingVoiceTrialTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sessions = mutableListOf<FakeSession>()
    private val trial = OnboardingVoiceTrial(scope) { callbacks -> FakeSession(callbacks).also { sessions.add(it) } }

    @After fun close() { trial.cancel(); scope.cancel() }

    @Test fun doubleTapCannotCreateTwoCapturesAndStopDrainsBeforeEditing() {
        trial.start(); trial.start()
        assertEquals(1, sessions.size)
        assertEquals(TrialPhase.PREPARING, trial.state.value.phase)
        sessions[0].beginCapture()
        trial.stop()
        assertEquals(TrialPhase.PROCESSING, trial.state.value.phase)
        assertEquals(1, sessions[0].stops)
        sessions[0].callbacks.onText("A completed sentence.")
        sessions[0].callbacks.onComplete(null)
        sessions[0].callbacks.onClosed()
        assertEquals(TrialPhase.IDLE, trial.state.value.phase)
        assertEquals("A completed sentence.", trial.state.value.text)
    }

    @Test fun leavingDuringModelLoadCancelsInsteadOfStartingAnInvisibleMicrophone() {
        trial.start()
        trial.stopForBackground()
        assertEquals(1, sessions[0].cancels)
        assertEquals(false, sessions[0].discarded)
        assertEquals(0, sessions[0].stops)
        sessions[0].callbacks.onReady()
        assertEquals(TrialPhase.IDLE, trial.state.value.phase)
    }

    @Test fun waveformStopDrainsCaptureEvenIfTheReadyCallbackIsStillInFlight() {
        trial.start()
        val session = sessions.single()
        // Native metrics are published on the capture thread just before the Main callback.
        session.metrics.started()
        trial.stop()
        session.callbacks.onReady()
        assertEquals(TrialPhase.PROCESSING, trial.state.value.phase)
        assertEquals(1, session.stops)
        assertEquals(0, session.cancels)
    }

    @Test fun endingBeforeReadyDoesNotDiscardAlreadyCapturedAudio() {
        trial.start()
        val session = sessions.single()
        session.metrics.started()
        session.metrics.captureEnded()
        trial.stopForBackground()
        session.callbacks.onReady()
        assertEquals(TrialPhase.PROCESSING, trial.state.value.phase)
        assertEquals(0, session.cancels)
        assertEquals(0, session.stops)
    }

    @Test fun cancelClosesThePreviewButDoesNotAdmitAnotherCaptureBeforeRelease() {
        trial.start()
        val first = sessions.single()
        trial.cancel()
        assertNull(trial.state.value.panel)
        trial.start()
        assertEquals(1, sessions.size)
        first.callbacks.onClosed()
        trial.start()
        assertEquals(2, sessions.size)
        first.callbacks.onClosed()
        assertNotNull(trial.state.value.panel)
    }

    @Test fun lateCallbacksCannotOverwriteANewerTrial() {
        trial.start()
        val old = sessions[0]
        trial.cancel(); old.callbacks.onClosed()
        trial.start()
        sessions[1].beginCapture()
        sessions[1].callbacks.onText("New words")
        old.callbacks.onText("Stale words")
        old.callbacks.onComplete("Old failure")
        assertEquals("New words", trial.state.value.text)
        assertNull(trial.state.value.error)
    }

    @Test fun completedTextSurvivesFailureAndPreviewMemoryIsBounded() {
        trial.setText("My note.")
        trial.start(); sessions[0].beginCapture()
        sessions[0].callbacks.onText("More words.")
        sessions[0].callbacks.onComplete("Audio interrupted")
        assertEquals("My note. More words.", trial.state.value.text)
        assertEquals("Audio interrupted", trial.state.value.error)
        sessions[0].callbacks.onClosed()
        trial.setText("x".repeat(10000))
        assertEquals(OnboardingVoiceTrial.MAX_TEXT, trial.state.value.text.length)
    }

    @Test fun panelPreviewBelongsOnlyToTheActiveTrialAndNeverIncludesTheTypedPrefix() {
        trial.setText("Typed words.")
        trial.start()
        assertNotNull(trial.state.value.panel)
        assertEquals("", trial.state.value.preview)
        sessions[0].beginCapture()
        sessions[0].callbacks.onText("Spoken words.")
        assertEquals("Spoken words.", trial.state.value.preview)
        assertEquals("Typed words. Spoken words.", trial.state.value.text)
        trial.stop()
        assertEquals("Spoken words.", trial.state.value.preview)
        sessions[0].callbacks.onComplete(null)
        assertNull(trial.state.value.panel)
        assertEquals("", trial.state.value.preview)
        trial.start()
        sessions[0].callbacks.onText("Stale words")
        assertEquals("", trial.state.value.preview)
        assertEquals("Typed words. Spoken words.", trial.state.value.text)
    }

    @Test fun openingSettingsAfterQueueAcceptanceDetachesTheTrialWithoutCancellingIt() {
        trial.setText("Typed words.")
        trial.start()
        val session = sessions.single()
        session.beginCapture()
        session.callbacks.onText("Already shown.")
        session.metrics.captureEnded()
        session.callbacks.onCaptureEnded()
        var opened = 0
        trial.confirmSettings(CapturePhase.PROCESSING) { opened++ }
        assertEquals(TrialPhase.PROCESSING, trial.state.value.phase)
        assertEquals(0, opened)
        session.settingsAccepted!!.invoke()
        assertEquals(1, opened)
        assertEquals(0, session.cancels)
        assertFalse("A queued tail no longer owns the microphone", trial.state.value.releasing)
        assertEquals("Typed words. Already shown.", trial.state.value.text)
        session.callbacks.onText("Queued words")
        assertEquals("Typed words. Already shown.", trial.state.value.text)
        session.callbacks.onClosed()
        assertFalse(trial.state.value.releasing)
    }

    @Test fun cancelCannotPretendToDiscardARequestedQueueHandoff() {
        trial.start()
        val session = sessions.single()
        session.beginCapture()
        session.metrics.captureEnded()
        session.callbacks.onCaptureEnded()
        var opened = false
        trial.confirmSettings(CapturePhase.PROCESSING) { opened = true }
        trial.cancel()
        assertEquals(TrialPhase.PROCESSING, trial.state.value.phase)
        assertEquals(0, session.cancels)
        session.settingsAccepted!!.invoke()
        assertTrue(opened)
    }

    private class FakeSession(val callbacks: OnboardingVoiceTrial.Callbacks) : OnboardingTrialSession {
        var settingsAccepted: (() -> Unit)? = null
        override val ownsNavigation: Boolean get() = settingsAccepted != null
        override fun confirmSettings(phase: CapturePhase, accepted: () -> Unit): Boolean {
            if (metrics.state.value.phase != phase) return false
            settingsAccepted = accepted
            return true
        }
        val metrics = CaptureMetrics()
        override val panel = VoiceTrialPanel(metrics.state, LiveSpeakerControl())
        var stops = 0
        var cancels = 0
        var discarded: Boolean? = null
        fun beginCapture() { metrics.started(); callbacks.onReady() }
        override fun start() = Unit
        override fun stop() { stops++ }
        override fun cancel(discard: Boolean) { cancels++; discarded = discard }
    }
}
