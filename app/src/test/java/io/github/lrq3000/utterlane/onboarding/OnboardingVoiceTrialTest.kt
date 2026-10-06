package io.github.lrq3000.utterlane.onboarding

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
        sessions[0].callbacks.onReady()
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
        assertEquals(0, sessions[0].stops)
        sessions[0].callbacks.onReady()
        assertEquals(TrialPhase.IDLE, trial.state.value.phase)
    }

    @Test fun lateCallbacksCannotOverwriteANewerTrial() {
        trial.start()
        val old = sessions[0]
        trial.cancel(); old.callbacks.onClosed()
        trial.start()
        sessions[1].callbacks.onReady()
        sessions[1].callbacks.onText("New words")
        old.callbacks.onText("Stale words")
        old.callbacks.onComplete("Old failure")
        assertEquals("New words", trial.state.value.text)
        assertNull(trial.state.value.error)
    }

    @Test fun completedTextSurvivesFailureAndPreviewMemoryIsBounded() {
        trial.setText("My note.")
        trial.start(); sessions[0].callbacks.onReady()
        sessions[0].callbacks.onText("More words.")
        sessions[0].callbacks.onComplete("Audio interrupted")
        assertEquals("My note. More words.", trial.state.value.text)
        assertEquals("Audio interrupted", trial.state.value.error)
        sessions[0].callbacks.onClosed()
        trial.setText("x".repeat(10000))
        assertEquals(OnboardingVoiceTrial.MAX_TEXT, trial.state.value.text.length)
    }

    private class FakeSession(val callbacks: OnboardingVoiceTrial.Callbacks) : OnboardingTrialSession {
        var stops = 0
        var cancels = 0
        override fun start() = Unit
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
    }
}
