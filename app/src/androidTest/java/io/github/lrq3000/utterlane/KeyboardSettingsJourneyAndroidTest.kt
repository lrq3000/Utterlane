package io.github.lrq3000.utterlane

import android.Manifest
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.asr.MicrophoneSessionFactory
import io.github.lrq3000.utterlane.asr.TranscriptCompletion
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.WavFile
import io.github.lrq3000.utterlane.service.VoiceInputActivity
import io.github.lrq3000.utterlane.service.StreamingDictationOverlay
import io.github.lrq3000.utterlane.service.StreamingTextTarget
import io.github.lrq3000.utterlane.settings.SettingsActivity
import io.github.lrq3000.utterlane.transcribe.QueueStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Controlled microphone PCM, real keyboard overlay, Settings, model inference, and queue storage. */
@RunWith(AndroidJUnit4::class)
class KeyboardSettingsJourneyAndroidTest {
    @Test fun floatingAndAccessibilityAdapterShowsTheSameGearWithoutAnEditor() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        assumeTrue(app.packageName.endsWith(".dictationfollowupqa"))
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        assertTrue(Settings.canDrawOverlays(app))
        val previous = app.microphoneSessions
        val source = OnboardingRecognitionAndroidTest.FixtureCapture(ShortArray(32000) { 500 })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        val priorIds = app.recordingHistory.list().map { it.id }.toSet()
        val overlay = java.util.concurrent.atomic.AtomicReference<StreamingDictationOverlay>()
        var settings: android.app.Activity? = null
        val ui = OnboardingTestUi().apply { prepare() }
        try {
            app.microphoneSessions = MicrophoneSessionFactory { source }
            instrumentation.runOnMainSync {
                overlay.set(StreamingDictationOverlay(app, scope, StreamingTextTarget(app) { false },
                    recordingChanged = {}, warning = {}, released = {}))
                overlay.get().start()
            }
            assertTrue(source.sent.await(15, TimeUnit.SECONDS))
            var visiblePanel: ViewGroup? = null
            withTimeout(10_000) {
                while (visiblePanel == null) {
                    instrumentation.runOnMainSync {
                        val window = StreamingDictationOverlay::class.java.getDeclaredField("overlay").apply { isAccessible = true }.get(overlay.get())
                        val native = window.javaClass.getDeclaredField("panel").apply { isAccessible = true }.get(window) as? ViewGroup
                        val gear = native?.findViewById<View>(R.id.recording_settings_gear)
                        if (gear?.isEnabled == true && gear.isShown && gear.getGlobalVisibleRect(Rect())) visiblePanel = native
                    }
                    if (visiblePanel == null) delay(30)
                }
            }
            instrumentation.runOnMainSync {
                val panel = checkNotNull(visiblePanel)
                val gear = panel.findViewById<View>(R.id.recording_settings_gear)
                val title = (gear.parent as ViewGroup).getChildAt(0) as TextView
                assertEquals(app.getString(R.string.capture_listening), title.text.toString())
                assertTrue(gear.left >= title.right)
                gear.performClick()
            }
            ui.screenshot("system-overlay-shared-dictation")
            instrumentation.runOnMainSync {
                val panel = checkNotNull(visiblePanel)
                panel.findViewById<View>(R.id.recording_settings_back).performClick()
                assertTrue(MicrophoneSession.isBusy())
                panel.findViewById<View>(R.id.recording_settings_gear).performClick()
                panel.findViewById<View>(R.id.recording_settings_confirm).performClick()
            }
            settings = monitor.waitForActivityWithTimeout(10000)
            assertNotNull("Explicit confirmation opens Utterlane Settings", settings)
            withTimeout(15000) {
                while (MicrophoneSession.hasSessions() || app.recordingHistory.list().any { it.id !in priorIds }) delay(40)
            }
        } finally {
            source.stop()
            instrumentation.runOnMainSync { overlay.get()?.cancel(false); settings?.finish() }
            instrumentation.removeMonitor(monitor)
            scope.cancel()
            app.microphoneSessions = previous
        }
    }

    @Test fun captureWarningCanBeDismissedAndOnlyFinishedAudioCanGoToTheQueue() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        assumeTrue(app.packageName.endsWith(".dictationsettingsqa") || app.packageName.endsWith(".dictationfollowupqa"))
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        assertTrue("The isolated QA app needs overlay access", Settings.canDrawOverlays(app))
        val queue = app.transcriptionQueue
        queue.initialized()
        assertTrue(queue.journal.records().isEmpty())
        assertTrue(app.modelManager.ensureVerified())
        val preferences = app.settingsRepository
        assertFalse(preferences.archival.first().enabled)
        val labels = preferences.diarizationEnabled.first()
        val history = preferences.audioHistoryEnabled.first()
        val retention = preferences.audioHistoryRetention.first()
        val priorFactory = app.microphoneSessions
        val priorAudio = app.recordingHistory.list().map { it.id }.toSet()
        val wav = File.createTempFile("keyboard-settings-qa-", ".wav", app.cacheDir)
        app.assets.open("onboarding/alice_wonderland_excerpt.wav").use { input -> wav.outputStream().use { input.copyTo(it) } }
        val sample = WavFile.read(wav, 0, ((wav.length() - 44) / 2).toInt())
        val ui = OnboardingTestUi().apply { prepare() }
        val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        var capture: OnboardingRecognitionAndroidTest.FixtureCapture? = null
        var voice: VoiceInputActivity? = null
        var settings: SettingsActivity? = null
        // API 28 may omit non-focusable application overlays from UiAutomation's window list even while they are visible. Inspect the actual attached, laid-out native panel in this process, not an unattached test double.
        fun panel(): ViewGroup {
            val overlay = VoiceInputActivity::class.java.getDeclaredField("recordingOverlay").apply { isAccessible = true }.get(voice)
            val view = overlay.javaClass.getDeclaredField("panel").apply { isAccessible = true }.get(overlay) as ViewGroup
            check(view.isAttachedToWindow && view.isShown && view.width > 0)
            return view
        }
        suspend fun uiClick(id: Int) = withTimeout(12_000) {
            while (true) {
                var clicked = false
                instrumentation.runOnMainSync {
                    val control = panel().findViewById<View>(id)
                    if (control?.isShown == true && control.isEnabled && control.getGlobalVisibleRect(Rect())) clicked = control.performClick()
                }
                if (clicked) break
                delay(40)
            }
        }
        fun hasWarning(message: Int): Boolean {
            fun contains(view: View): Boolean = view.isShown && ((view as? TextView)?.text?.toString() == app.getString(message) ||
                view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) })
            var result = false
            instrumentation.runOnMainSync { result = contains(panel()) }
            return result
        }
        fun startRecording(pcm: ShortArray) {
            val source = OnboardingRecognitionAndroidTest.FixtureCapture(pcm)
            capture = source
            app.microphoneSessions = MicrophoneSessionFactory { source }
            voice = instrumentation.startActivitySync(Intent(app, VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VoiceInputActivity
            assertTrue(source.sent.await(15, TimeUnit.SECONDS))
        }
        fun openedSettings(): SettingsActivity = checkNotNull(monitor.waitForActivityWithTimeout(10_000) as? SettingsActivity)
        try {
            preferences.setDiarizationEnabled(false)
            preferences.setAudioHistoryEnabled(true)
            preferences.setAudioHistoryRetention(HistoryRetention.FOREVER)
            startRecording(sample)
            uiClick(R.id.recording_settings_gear)
            assertTrue(hasWarning(R.string.dictation_settings_discard_warning))
            ui.screenshot("keyboard-settings-recording-warning")
            uiClick(R.id.recording_settings_back)
            assertFalse(hasWarning(R.string.dictation_settings_discard_warning))
            assertTrue(MicrophoneSession.isBusy())
            uiClick(R.id.recording_settings_gear)
            uiClick(R.id.recording_settings_confirm)
            settings = openedSettings()
            withTimeout(20_000) {
                while (MicrophoneSession.hasSessions() || app.recordingHistory.list().any { it.id !in priorAudio }) delay(50)
            }
            assertTrue("Discard must not publish a queue job", queue.rows.value.isEmpty())
            instrumentation.runOnMainSync { settings?.finish() }

            preferences.setAudioHistoryEnabled(false)
            // Enough real speech to keep the recognizer busy after microphone capture ends.
            startRecording(ShortArray(sample.size * 3) { sample[it % sample.size] })
            uiClick(R.id.recording_done)
            uiClick(R.id.recording_settings_gear)
            assertTrue(hasWarning(R.string.dictation_settings_background_warning))
            ui.screenshot("keyboard-settings-processing-warning")
            uiClick(R.id.recording_settings_back)
            assertTrue("Declining backgrounding cannot publish a job", queue.rows.value.isEmpty())
            uiClick(R.id.recording_settings_gear)
            uiClick(R.id.recording_settings_confirm)
            settings = openedSettings()
            val row = withTimeout(180_000) { queue.rows.first { rows -> rows.singleOrNull()?.stage?.reviewable == true }.single() }
            assertEquals(row.message, QueueStage.READY, row.stage)
            assertEquals(row.audio, TranscriptSource.read(queue.target(row.id)).audioId)
            assertEquals(TranscriptCompletion.COMPLETE, TranscriptSource.read(queue.target(row.id)).completion)
            assertTrue(queue.target(row.id).readText().contains("rabbit", ignoreCase = true))
            assertTrue("Queue review owns non-history input until the user decides", app.recordingHistory.get(row.audio).temporary)
            ui.screenshot("keyboard-settings-queued")
        } finally {
            capture?.stop()
            instrumentation.runOnMainSync { settings?.finish(); voice?.finish() }
            instrumentation.removeMonitor(monitor)
            app.microphoneSessions = priorFactory
            preferences.setDiarizationEnabled(labels)
            preferences.setAudioHistoryEnabled(history)
            preferences.setAudioHistoryRetention(retention)
            wav.delete()
        }
    }
}
