package io.github.lrq3000.utterlane

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.AudioCapture
import io.github.lrq3000.utterlane.asr.CaptureObserver
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.asr.MicrophoneSessionFactory
import io.github.lrq3000.utterlane.onboarding.*
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Requires a verified speech model installed in this isolated QA app. Only the
 * microphone source is substituted; decoding, workers, model and UI are real. */
@RunWith(AndroidJUnit4::class)
class OnboardingRecognitionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun speakerOptInKeepsAnExistingExplicitCount() = runBlocking {
        assertTrue("Import the speaker model before this optional-model check", app.diarizationModels.ensureVerified())
        val enabled = app.settingsRepository.diarizationEnabled.first()
        val count = app.settingsRepository.speakerCount.first()
        try {
            app.settingsRepository.setDiarizationEnabled(false)
            app.settingsRepository.setSpeakerCount(3)
            AppOnboardingServices(app).setSpeakers(true)
            assertTrue(app.settingsRepository.diarizationEnabled.first())
            assertEquals(3, app.settingsRepository.speakerCount.first())
        } finally {
            app.settingsRepository.setSpeakerCount(count)
            app.settingsRepository.setDiarizationEnabled(enabled)
        }
    }

    @Test fun speechTrialInsertsRealRecognitionWithoutKeyboardSetup() = runBlocking {
        ui.prepare()
        app.modelManager.initializeSelection()
        assertTrue("Install a model in the QA app before inference checks", app.modelManager.ensureVerified())
        val repository = OnboardingRepository(app)
        val progress = repository.progress.first()
        val oldFactory = app.microphoneSessions
        val oldSpeakers = app.settingsRepository.diarizationEnabled.first()
        val sample = OnboardingSample(app).shareIntent()
        @Suppress("DEPRECATION") val uri = sample.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        // The bundled fixture is only nine seconds, so retaining its PCM in the
        // test is bounded. Production audio remains incrementally decoded.
        val pieces = mutableListOf<ShortArray>()
        AudioDecoder(app).decode(uri, { pieces.add(it) })
        val pcm = ShortArray(pieces.sumOf { it.size })
        var offset = 0
        pieces.forEach { it.copyInto(pcm, offset); offset += it.size }
        val capture = FixtureCapture(pcm)
        var activity: OnboardingActivity? = null
        try {
            app.settingsRepository.setDiarizationEnabled(false)
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${app.packageName} android.permission.RECORD_AUDIO")).use { it.readBytes() }
            app.microphoneSessions = MicrophoneSessionFactory { capture }
            repository.update { it.copy(initialized = true, stepId = OnboardingStep.VOICE_TRIAL.id, modelId = app.modelManager.selected.value.id) }
            activity = instrumentation.startActivitySync(Intent(app, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OnboardingActivity
            ui.click("onboarding_record")
            assertTrue("Sample did not reach the real capture pipeline", capture.sent.await(120, TimeUnit.SECONDS))
            ui.click("onboarding_record")
            val text = ui.recognizedText(id = "onboarding_transcript")
            assertTrue("Expected reading content: $text", text.contains("late", ignoreCase = true))
            ui.screenshot("voice-trial-result")
        } finally {
            capture.stop()
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (MicrophoneSession.isBusy() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            app.microphoneSessions = oldFactory
            app.settingsRepository.setDiarizationEnabled(oldSpeakers)
            repository.update { progress }
        }
    }

    @Test fun sampleShareReachesTheRealAudioReceiverAndProducesText() = runBlocking {
        ui.prepare()
        app.modelManager.initializeSelection()
        assertTrue("Install a model in the QA app before inference checks", app.modelManager.ensureVerified())
        val oldSpeakers = app.settingsRepository.diarizationEnabled.first()
        val repository = OnboardingRepository(app)
        val progress = repository.progress.first()
        var caller: OnboardingActivity? = null
        var activity: TranscribeActivity? = null
        val monitor = instrumentation.addMonitor(TranscribeActivity::class.java.name, null, false)
        try {
            app.settingsRepository.setDiarizationEnabled(false)
            repository.update { it.copy(initialized = true, stepId = OnboardingStep.FILE_TRIAL.id, modelId = app.modelManager.selected.value.id) }
            caller = instrumentation.startActivitySync(Intent(app, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OnboardingActivity
            ui.node("onboarding_page_try-file").recycle()
            val send = OnboardingSample(app).shareIntent().setClass(app, TranscribeActivity::class.java)
            // Match the real foreground share handoff, rather than making the
            // dialog receiver the root of a background instrumentation task.
            instrumentation.runOnMainSync { caller!!.startActivity(send) }
            activity = monitor.waitForActivityWithTimeout(10000) as? TranscribeActivity
            assertNotNull("The share receiver did not open", activity)
            instrumentation.waitForIdleSync()
            val copy = ui.textNode(app.getString(R.string.transcribe_copy))
            val windowId = copy.windowId
            @Suppress("DEPRECATION") copy.recycle()
            assertTrue(windowId >= 0)
            // Scope to the receiver's own window: the guide underneath contains
            // the sample title, which must never count as recognized speech.
            val text = ui.recognizedText(windowId = windowId)
            assertTrue("Expected reading content: $text", text.contains("late", ignoreCase = true))
            ui.screenshot("sample-share-result")
        } finally {
            instrumentation.removeMonitor(monitor)
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            caller?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            app.settingsRepository.setDiarizationEnabled(oldSpeakers)
            repository.update { progress }
        }
    }

    private class FixtureCapture(private val pcm: ShortArray) : AudioCapture {
        private val stopped = AtomicBoolean(false)
        private var observer: CaptureObserver? = null
        val sent = CountDownLatch(1)
        override fun setObserver(observer: CaptureObserver) { this.observer = observer }
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            observer?.onStarted()
            var offset = 0
            while (offset < pcm.size && !stopped.get() && shouldContinue()) {
                val end = minOf(offset + 3200, pcm.size)
                onSamples(pcm.copyOfRange(offset, end)); offset = end
                Thread.sleep(20)
            }
            sent.countDown()
            while (!stopped.get() && shouldContinue()) Thread.sleep(20)
        }
        override fun stop() { stopped.set(true) }
    }
}
