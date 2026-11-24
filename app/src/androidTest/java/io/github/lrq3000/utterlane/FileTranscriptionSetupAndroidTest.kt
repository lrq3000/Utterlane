package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    private inline fun android.view.accessibility.AccessibilityNodeInfo.useNode(
        check: (android.view.accessibility.AccessibilityNodeInfo) -> Unit
    ) { try { check(this) } finally { recycle() } }
}
