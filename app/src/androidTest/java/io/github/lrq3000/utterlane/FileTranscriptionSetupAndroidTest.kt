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
            ui.node("file_setup_speakers").useNode { assertFalse(it.isChecked) }
            ui.click("file_setup_speakers")
            ui.node("file_setup_speakers").useNode { assertTrue(it.isChecked) }
            ui.click("file_setup_cancel")
            assertEquals(recordings, app.recordingHistory.list().map { it.id })
            screen = launchNativeActivity(share())
            ui.node("file_setup_speakers").useNode { assertFalse(it.isChecked) }
            assertTrue(settings.diarizationEnabled.first())
        } finally {
            instrumentation.runOnMainSync { screen?.finish() }
            settings.setSpeakerCount(previousCount)
            settings.setDiarizationEnabled(previousEnabled)
            source.delete()
        }
    }

    @Test fun homePickerReturnsOnlyTheConfirmedOptionsToItsExistingWorkspace() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val ui = OnboardingTestUi()
        val settings = app.settingsRepository
        val oldDefault = settings.diarizationEnabled.first()
        val oldCount = settings.speakerCount.first()
        val onboarding = OnboardingRepository(app)
        val oldOnboarding = onboarding.progress.first()
        val recordings = app.recordingHistory.list().map { it.id }.toSet()
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
            ui.node("file_setup_speakers").useNode { assertFalse(it.isChecked) }
            ui.click("file_setup_speakers")
            ui.click("file_setup_start")
            val result = withTimeout(30000) {
                app.homeController.state.first { !it.busy && it.model != null && it.result.audio != null }
            }
            assertEquals(true, result.model?.input?.fileOptions?.speakerLabels)
            assertFalse(settings.diarizationEnabled.first())
        } finally {
            instrumentation.removeMonitor(systemPicker)
            instrumentation.runOnMainSync { app.homeController.dismiss(); screen?.finish() }
            withTimeoutOrNull(10000) { app.homeController.state.first { it.model == null && !it.busy } }
            settings.setDiarizationEnabled(oldDefault)
            settings.setSpeakerCount(oldCount)
            onboarding.update { oldOnboarding }
            app.recordingHistory.list().filter { it.id !in recordings }.forEach { app.recordingHistory.delete(it.id) }
            source.delete()
        }
    }

    private inline fun android.view.accessibility.AccessibilityNodeInfo.useNode(
        check: (android.view.accessibility.AccessibilityNodeInfo) -> Unit
    ) { try { check(this) } finally { recycle() } }
}
