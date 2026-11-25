package io.github.lrq3000.utterlane

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.MicrophoneSessionFactory
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveSpeakerControlAndroidTest {
    @Test fun waveformToggleDoesNotStopRecordingOrChangeTheDefault() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val ui = OnboardingTestUi()
        val settings = app.settingsRepository
        val previous = settings.diarizationEnabled.first()
        val onboarding = OnboardingRepository(app)
        val previousOnboarding = onboarding.progress.first()
        val previousFactory = app.microphoneSessions
        val ids = app.recordingHistory.list().map { it.id }.toSet()
        val capture = OnboardingRecognitionAndroidTest.FixtureCapture(ShortArray(16000) { 500 })
        var screen: Activity? = null
        try {
            onboarding.complete()
            settings.setDiarizationEnabled(false)
            app.microphoneSessions = MicrophoneSessionFactory { capture }
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.RECORD_AUDIO")
            ui.prepare()
            screen = launchNativeActivity(HomeActivity.intent(app))
            ui.node("home_screen").recycle()
            instrumentation.runOnMainSync { app.homeController.record() }
            withTimeout(10000) { app.homeController.state.first { it.liveSpeakers == false && it.capture.active } }
            fun visual(checked: Boolean) {
                if (InstrumentationRegistry.getArguments().getString("speakerVisualEvidence") != "true") return
                ui.scrollTo("home_live_speakers")
                ui.awaitChecked("home_live_speakers", checked)
                val node = ui.node("home_live_speakers")
                val bounds = android.graphics.Rect()
                try { node.getBoundsInScreen(bounds) } finally { node.recycle() }
                saveSpeakerCrop("home-$checked", bounds)
            }
            visual(false)
            ui.click("home_live_speakers")
            val on = withTimeout(10000) { app.homeController.state.first { it.liveSpeakers == true } }
            assertEquals("RECORDING", on.capture.phase.name)
            visual(true)
            ui.click("home_live_speakers")
            val off = withTimeout(10000) { app.homeController.state.first { it.liveSpeakers == false } }
            assertEquals("RECORDING", off.capture.phase.name)
            assertFalse(settings.diarizationEnabled.first())
            instrumentation.runOnMainSync { app.homeController.record() }
            withTimeout(20000) { app.homeController.state.first { !it.busy } }
        } finally {
            capture.stop()
            withTimeoutOrNull(20000) { app.homeController.state.first { !it.busy } }
            instrumentation.runOnMainSync { app.homeController.dismiss(); screen?.finish() }
            app.microphoneSessions = previousFactory
            settings.setDiarizationEnabled(previous)
            onboarding.update { previousOnboarding }
            app.recordingHistory.list().filter { it.id !in ids }.forEach { app.recordingHistory.delete(it.id) }
        }
    }
}
