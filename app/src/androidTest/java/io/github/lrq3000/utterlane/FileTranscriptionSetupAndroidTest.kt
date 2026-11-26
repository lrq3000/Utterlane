package io.github.lrq3000.utterlane

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.WavFile
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileTranscriptionSetupAndroidTest {
    @Test fun sharedFileCancelLeavesNoImportAndNextFileStartsWithLabelsOff() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val ui = OnboardingTestUi()
        val source = File.createTempFile("setup-fixture-", ".wav", app.cacheDir)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
        val settings = app.settingsRepository
        val previousEnabled = settings.diarizationEnabled.first()
        val previousCount = settings.speakerCount.first()
        val recordings = app.recordingHistory.list().map { it.id }
        var screen: Activity? = null
        try {
            settings.setDiarizationEnabled(true)
            settings.setSpeakerCount(1) // Fixed one needs no separate speaker model.
            ui.prepare()
            fun share() = Intent(Intent.ACTION_SEND).setPackage(app.packageName).setType("audio/wav")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            screen = launchNativeActivity(share())
            ui.awaitChecked("file_setup_speakers", false)
            ui.click("file_setup_speakers")
            ui.awaitChecked("file_setup_speakers", true)
            ui.click("file_setup_cancel")
            assertEquals(recordings, app.recordingHistory.list().map { it.id })
            screen = launchNativeActivity(share())
            ui.awaitChecked("file_setup_speakers", false)
            assertTrue(settings.diarizationEnabled.first())
        } finally {
            instrumentation.runOnMainSync { screen?.finish() }
            settings.setSpeakerCount(previousCount)
            settings.setDiarizationEnabled(previousEnabled)
            source.delete()
        }
    }

    @Test fun homePickerQueuesOnlyTheConfirmedOptionsWithoutChangingLiveDefaults() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val ui = OnboardingTestUi()
        val settings = app.settingsRepository
        val oldDefault = settings.diarizationEnabled.first()
        val oldCount = settings.speakerCount.first()
        val onboarding = OnboardingRepository(app)
        val oldOnboarding = onboarding.progress.first()
        val existing = app.transcriptionQueue.journal.records().map { it.id }.toSet()
        val source = File.createTempFile("picker-fixture-", ".wav", app.cacheDir)
        WavFile(source).use { it.append(ShortArray(3200)) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
        val systemPicker = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addDataType("audio/*") },
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
        var screen: Activity? = null
        try {
            onboarding.complete()
            settings.setDiarizationEnabled(false)
            settings.setSpeakerCount(1)
            ui.prepare()
            screen = launchNativeActivity(HomeActivity.intent(app))
            ui.click("home_load_audio")
            ui.awaitChecked("file_setup_speakers", false)
            ui.click("file_setup_speakers")
            ui.click("file_setup_start")
            val result = withTimeout(30000) { app.transcriptionQueue.rows.first { rows ->
                rows.any { it.id !in existing && it.label == source.name }
            }.single { it.id !in existing && it.label == source.name } }
            assertTrue(result.speakers)
            assertEquals(true, app.recordingHistory.get(result.audio).fileSpeakerLabels)
            assertFalse(settings.diarizationEnabled.first())
        } finally {
            instrumentation.removeMonitor(systemPicker)
            instrumentation.runOnMainSync { screen?.finish() }
            settings.setDiarizationEnabled(oldDefault)
            settings.setSpeakerCount(oldCount)
            onboarding.update { oldOnboarding }
            val owned = app.transcriptionQueue.journal.records().filter { it.id !in existing && it.label == source.name }
            for (job in owned) {
                withTimeout(120000) { app.transcriptionQueue.rows.first { rows -> rows.any { it.id == job.id && it.stage.reviewable } } }
                app.transcriptionQueue.acknowledge(job.id)
                app.recordingHistory.delete(job.audio)
                app.transcriptHistory.find(job.textId)?.let { app.transcriptHistory.delete(it.id) }
                io.github.lrq3000.utterlane.asr.TranscriptStore.deleteArtifacts(app.transcriptionQueue.target(job.id))
            }
            source.delete()
        }
    }
}
