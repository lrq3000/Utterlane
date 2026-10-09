package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.asr.MicrophoneSessionFactory
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.onboarding.OnboardingSample
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.util.UUID

/** Real isolated-worker recognition and native Home; only microphone PCM comes
 * from the checked public-domain nine-second fixture. Never captures private speech. */
@RunWith(AndroidJUnit4::class)
class HomeRecognitionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun realSpeechStreamsIntoHomeAndKeepsIndependentLabeledHistory() = runBlocking<Unit> {
        assertTrue("Use the isolated Home QA identity", app.packageName.endsWith(".dhome"))
        ui.prepare()
        app.modelManager.initializeSelection()
        val oldModel = app.modelManager.selected.value
        val onboarding = OnboardingRepository(app)
        val oldOnboarding = onboarding.progress.first()
        val oldFactory = app.microphoneSessions
        val settings = app.settingsRepository
        val oldSpeakers = settings.diarizationEnabled.first()
        val oldCount = settings.speakerCount.first()
        val oldAudio = settings.audioHistoryEnabled.first()
        val oldAudioRetention = settings.audioHistoryRetention.first()
        val oldText = settings.transcriptHistoryEnabled.first()
        val oldTextRetention = settings.transcriptHistoryRetention.first()
        var home: Activity? = null
        var capture: OnboardingRecognitionAndroidTest.FixtureCapture? = null
        var audioId: String? = null
        var textId: String? = null
        var observer: Job? = null
        try {
            app.recognizerManager.selectModel(ModelCatalog.DEFAULT)
            assertTrue("Seed the verified Ultra Q8 model before this acceptance test", app.modelManager.ensureVerified())
            onboarding.complete()
            settings.setAudioHistoryEnabled(false)
            settings.setAudioHistoryRetention(HistoryRetention.NONE)
            settings.setTranscriptHistoryEnabled(true)
            settings.setTranscriptHistoryRetention(HistoryRetention.DAY)
            // Fixed-one is a supported label mode requiring no auxiliary model.
            // This exercises actual label emission/metadata without an accuracy benchmark.
            settings.setSpeakerCount(1)
            settings.setDiarizationEnabled(true)
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.RECORD_AUDIO")
            @Suppress("DEPRECATION")
            val uri = OnboardingSample(app).shareIntent().getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            val pieces = mutableListOf<ShortArray>()
            AudioDecoder(app).decode(uri, { pieces.add(it) })
            val pcm = ShortArray(pieces.sumOf { it.size })
            var offset = 0
            pieces.forEach { it.copyInto(pcm, offset); offset += it.size }
            capture = OnboardingRecognitionAndroidTest.FixtureCapture(pcm)
            app.microphoneSessions = MicrophoneSessionFactory { capture!! }
            home = instrumentation.startActivitySync(HomeActivity.intent(app))
            ui.node("home_screen").recycle()
            assertNull("Start with an empty QA workspace", app.homeController.state.value.model)
            val streamed = AtomicBoolean(false)
            observer = app.applicationScope.launch {
                app.homeController.state.collect { state ->
                    if (state.capture.active && state.result.preview.isNotBlank()) streamed.set(true)
                }
            }
            instrumentation.runOnMainSync { app.homeController.record() }
            assertTrue("Public PCM must reach capture independently of model preparation", capture!!.sent.await(60, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { app.homeController.record() }
            val result = withTimeout(180000) { app.homeController.state.first { !it.busy && it.model != null } }
            assertNull(result.message)
            assertTrue("Expected actual public reading: ${result.result.preview}", result.result.preview.contains("rabbit", ignoreCase = true))
            assertTrue("Text must arrive before the capture/result owner completes", streamed.get())
            val model = checkNotNull(result.model)
            val audio = checkNotNull(result.result.audio)
            audioId = audio.id
            textId = checkNotNull(result.result.transcriptId)
            val saved = app.transcriptHistory.get(textId!!)
            assertEquals(audio.started, saved.created)
            assertEquals(pcm.size * 1000L / 16000, saved.durationMs)
            assertTrue(saved.speakerLabels)
            assertTrue(audio.speakerLabels)
            assertTrue(audio.temporary)
            val text = checkNotNull(result.result.store).readForTransfer()!!
            assertTrue(ui.recognizedText().contains("rabbit", ignoreCase = true))
            ui.screenshot("home-real-speech-result")
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("QA", "")) }
            ui.clickText(app.getString(R.string.transcribe_copy))
            withTimeout(5000) { while (clipboard.primaryClip?.getItemAt(0)?.text?.toString() != text) delay(20) }
            // A picker result is not yet replacement input: a revoked/missing
            // source must not erase the usable recording currently on screen.
            val missing = File(app.cacheDir, "missing-home-${UUID.randomUUID()}.wav")
            instrumentation.runOnMainSync { app.homeController.load(Uri.fromFile(missing)) }
            val rejected = withTimeout(10000) { app.homeController.state.first {
                !it.busy && (it.message != null || it.result.message != null)
            } }
            assertSame("A failed file load must preserve the previous workspace", model, rejected.model)
            assertEquals(text, rejected.result.store!!.readForTransfer())
            assertTrue(audio.part(0).isFile)
            instrumentation.runOnMainSync { model.saveAudioToHistory() }
            withTimeout(10000) { model.state.first { !it.saving && it.audio?.pinned == true } }
            ui.click("home_nav_audio")
            ui.node("history_entry_${audio.id}").recycle()
            ui.screenshot("home-real-audio-history")
            ui.click("home_nav_transcripts")
            ui.node("history_entry_${saved.id}").recycle()
            ui.screenshot("home-real-transcript-history")
        } finally {
            observer?.cancel()
            capture?.stop()
            withTimeoutOrNull(180000) { app.homeController.state.first { !it.busy } }
            if (MicrophoneSession.isBusy()) MicrophoneSession.resetActive()
            instrumentation.runOnMainSync { app.homeController.dismiss(); home?.finish() }
            withTimeoutOrNull(10000) { app.homeController.state.first { it.model == null } }
            audioId?.let(app.recordingHistory::delete)
            textId?.let(app.transcriptHistory::delete)
            app.microphoneSessions = oldFactory
            settings.setDiarizationEnabled(oldSpeakers)
            settings.setSpeakerCount(oldCount)
            settings.setAudioHistoryEnabled(oldAudio)
            settings.setAudioHistoryRetention(oldAudioRetention)
            settings.setTranscriptHistoryEnabled(oldText)
            settings.setTranscriptHistoryRetention(oldTextRetention)
            app.recognizerManager.selectModel(oldModel)
            onboarding.update { oldOnboarding }
        }
    }
}
