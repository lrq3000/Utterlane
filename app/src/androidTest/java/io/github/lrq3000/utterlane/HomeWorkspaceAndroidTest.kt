package io.github.lrq3000.utterlane

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.AudioCapture
import io.github.lrq3000.utterlane.asr.CaptureObserver
import io.github.lrq3000.utterlane.asr.MicrophoneSessionFactory
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeDestination
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Uses deterministic PCM with the real service, history and transcription owners. */
@RunWith(AndroidJUnit4::class)
class HomeWorkspaceAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun captureSurvivesNavigationAndRecreationAndNextReleasesTemporaryAudio() = runBlocking<Unit> {
        assertTrue("Run on the parent-owned isolated QA identity", app.packageName.endsWith(".dhome"))
        ui.prepare()
        val onboarding = OnboardingRepository(app)
        val previousOnboarding = onboarding.progress.first()
        val audioEnabled = app.settingsRepository.audioHistoryEnabled.first()
        val audioRetention = app.settingsRepository.audioHistoryRetention.first()
        val textEnabled = app.settingsRepository.transcriptHistoryEnabled.first()
        val oldFactory = app.microphoneSessions
        val source = SlowCapture()
        val secondSource = SlowCapture()
        var home: Activity? = null
        var settings: Activity? = null
        var id: String? = null
        try {
            onboarding.complete()
            app.settingsRepository.setAudioHistoryEnabled(false)
            app.settingsRepository.setAudioHistoryRetention(HistoryRetention.NONE)
            app.settingsRepository.setTranscriptHistoryEnabled(false)
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.RECORD_AUDIO")
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.POST_NOTIFICATIONS")
            home = instrumentation.startActivitySync(HomeActivity.intent(app))
            ui.node("home_screen").recycle()
            assertNull("Use an empty Home workspace, not a user's retained result", app.homeController.state.value.model)
            app.microphoneSessions = MicrophoneSessionFactory { source }
            instrumentation.runOnMainSync { app.homeController.record() }
            withTimeout(10000) { app.homeController.state.first { it.metrics.capturedSamples >= 3200 } }
            ui.click("home_nav_audio")
            ui.node("history_screen").recycle()
            val before = app.homeController.state.value.metrics.capturedSamples
            settings = instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java)
                .putExtra(io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(10000) { app.homeController.state.first { it.metrics.capturedSamples > before } }
            ui.click("home_nav_record")
            ui.node("home_screen").recycle()
            val old = home!!
            val recreation = instrumentation.addMonitor(HomeActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { old.recreate() }
                home = recreation.waitForActivityWithTimeout(10000)
                assertNotNull(home)
                assertTrue(app.homeController.state.value.capture.active)
            } finally { instrumentation.removeMonitor(recreation) }
            val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 1010 }
            notification.notification.actions.first().actionIntent.send()
            val result = withTimeout(120000) { app.homeController.state.first { !it.busy && it.model != null } }
            withTimeout(10000) {
                @Suppress("DEPRECATION")
                while (app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                        .any { it.service.className == "io.github.lrq3000.utterlane.home.HomeSessionService" }) delay(20)
            }
            id = result.result.audio?.id
            val audio = app.recordingHistory.get(checkNotNull(id))
            assertTrue(audio.temporary)
            assertTrue(audio.samples >= 3200)
            assertTrue(audio.part(0).isFile)
            assertNull(result.result.transcriptId)
            app.microphoneSessions = MicrophoneSessionFactory { secondSource }
            instrumentation.runOnMainSync { app.homeController.record() }
            withTimeout(10000) { app.homeController.state.first {
                it.capture.phase == io.github.lrq3000.utterlane.home.HomeCapturePhase.RECORDING && it.metrics.capturedSamples >= 1600
            } }
            withTimeout(10000) { while (audio.directory.exists()) delay(20) }
            instrumentation.runOnMainSync { app.homeController.record() }
            val next = withTimeout(120000) { app.homeController.state.first { !it.busy && it.model != null } }
            id = checkNotNull(next.result.audio).id
            assertNotEquals(audio.id, id)
            instrumentation.runOnMainSync { app.homeController.dismiss() }
            withTimeout(10000) { app.homeController.state.first { it.model == null && !it.busy } }
            withTimeout(10000) { while (next.result.audio!!.directory.exists()) delay(20) }
        } finally {
            source.stop(); secondSource.stop()
            withTimeoutOrNull(120000) { app.homeController.state.first { !it.busy } }
            instrumentation.runOnMainSync { app.homeController.dismiss(); settings?.finish(); home?.finish() }
            id?.let(app.recordingHistory::delete)
            app.microphoneSessions = oldFactory
            app.settingsRepository.setAudioHistoryEnabled(audioEnabled)
            app.settingsRepository.setAudioHistoryRetention(audioRetention)
            app.settingsRepository.setTranscriptHistoryEnabled(textEnabled)
            onboarding.update { previousOnboarding }
        }
    }

    @Test fun localFileFailureRetainsOwnedCopyAndRecoveryAfterMicrophoneDenial() = runBlocking<Unit> {
        assertTrue("Run on the parent-owned isolated QA identity", app.packageName.endsWith(".dhome"))
        ui.prepare()
        val onboarding = OnboardingRepository(app)
        val previousOnboarding = onboarding.progress.first()
        val oldFactory = app.microphoneSessions
        val original = File.createTempFile("home-import-", ".wav", app.cacheDir)
        // Owned copying succeeds, but decoding this deliberately invalid WAV
        // must fail regardless of which recognition model the QA app installed.
        original.writeBytes(byteArrayOf(1, 2, 3, 4))
        var home: Activity? = null
        var id: String? = null
        try {
            onboarding.complete()
            home = instrumentation.startActivitySync(HomeActivity.intent(app))
            ui.node("home_screen").recycle()
            assertNull("Use an empty Home workspace", app.homeController.state.value.model)
            app.microphoneSessions = MicrophoneSessionFactory { error("File loading must not open a microphone") }
            instrumentation.runOnMainSync {
                app.homeController.microphoneDenied()
                assertTrue(app.homeController.state.value.permissionDenied)
                app.homeController.load(Uri.fromFile(original))
                assertFalse(app.homeController.state.value.permissionDenied)
            }
            val result = withTimeout(120000) { app.homeController.state.first { it.model != null && !it.busy } }
            assertFalse("File errors must offer file recovery rather than microphone settings", result.permissionDenied)
            assertNotNull(result.result.message)
            val audio = checkNotNull(result.result.audio)
            id = audio.id
            assertNotEquals(original.canonicalPath, audio.part(0).canonicalPath)
            assertArrayEquals(original.readBytes(), audio.part(0).readBytes())
            original.delete()
            assertTrue("Imported working audio must not depend on the picker source", audio.part(0).isFile)
            instrumentation.runOnMainSync { app.homeController.dismiss() }
            withTimeout(10000) { app.homeController.state.first { it.model == null } }
        } finally {
            instrumentation.runOnMainSync { app.homeController.dismiss(); home?.finish() }
            app.microphoneSessions = oldFactory
            id?.let(app.recordingHistory::delete)
            original.delete()
            onboarding.update { previousOnboarding }
        }
    }

    private class SlowCapture : AudioCapture {
        private val stopped = AtomicBoolean(false)
        private var observer: CaptureObserver? = null
        override fun setObserver(observer: CaptureObserver) { this.observer = observer }
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            observer?.onStarted()
            while (!stopped.get() && shouldContinue()) {
                onSamples(ShortArray(320) { 1234 })
                Thread.sleep(20)
            }
        }
        override fun stop() { stopped.set(true) }
    }
}
