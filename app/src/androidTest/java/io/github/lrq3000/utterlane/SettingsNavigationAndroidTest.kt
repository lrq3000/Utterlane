package io.github.lrq3000.utterlane

import android.app.Activity
import android.app.Instrumentation.ActivityMonitor
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator
import io.github.lrq3000.utterlane.history.RecordingRecovery
import io.github.lrq3000.utterlane.asr.ModelArtifact
import io.github.lrq3000.utterlane.asr.ModelBackend
import io.github.lrq3000.utterlane.asr.ModelDefinition
import io.github.lrq3000.utterlane.asr.ModelManager
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsNavigationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun directoryNavigatesToEveryPageAndBack(): Unit = runBlocking {
        ui.prepare()
        val activity = launchNativeActivity(Intent(app, SettingsActivity::class.java)
            .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))
        try {
            val inventory = mapOf(
                "speech" to listOf(R.string.model_choose, R.string.corrections_manage, R.string.advanced_show_options),
                "speakers" to listOf(R.string.speaker_model_heading, R.string.diarization_enable, R.string.diarization_count),
                "voice" to listOf(R.string.permission_mic_title, R.string.keyboard_voice_ime,
                    R.string.accessibility_text_injection, R.string.floating_enable),
                "files" to listOf(R.string.transcription_audio_access, R.string.transcription_monitor_folders, R.string.transcription_share_open),
                "storage" to listOf(R.string.history_auto_audio, R.string.history_auto_text, R.string.import_reset_choices,
                    R.string.history_title, R.string.transcript_history_title, R.string.archive_title),
                "appearance" to listOf(R.string.app_language, R.string.show_transcription_stream_statistics)
            )
            for ((page, labels) in inventory) {
                ui.scrollTo("settings_open_$page")
                ui.click("settings_open_$page", scroll = false)
                ui.node("settings_page_$page").recycle()
                labels.forEach { ui.scrollToText(activity.getString(it)) }
                if (page == "storage") ui.scrollTo("archive_markdown_folder")
                back()
                ui.node("settings_page_root").recycle()
            }
            ui.scrollTo("settings_onboarding")
            ui.scrollToText(activity.getString(R.string.privacy_policy))
        } finally { finish(activity) }
    }

    @Test fun aboutShowsInstalledVersionAndProjectInformation(): Unit = runBlocking {
        ui.prepare()
        val activity = launchNativeActivity(Intent(app, SettingsActivity::class.java)
            .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))
        try {
            ui.click("settings_open_about")
            ui.node("settings_page_about").recycle()
            ui.scrollToText(app.getString(R.string.app_name))
            @Suppress("DEPRECATION")
            val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            ui.scrollToText("Version $installed")
            ui.scrollToText("Free and open-source software, made with love ❤️")
            ui.scrollToText("Stephen Karl Larroque")
            ui.scrollToText("Apache-2.0")
            ui.scrollToText("github.com/lrq3000/Utterlane")
            var destination: Intent? = null
            val link = object : ActivityMonitor() {
                override fun onStartActivity(intent: Intent): ActivityResult? {
                    if (intent.action != Intent.ACTION_VIEW) return null
                    destination = intent
                    return ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(link)
            try {
                ui.click("settings_about_github", scroll = false)
                instrumentation.waitForIdleSync()
                assertEquals("https://github.com/lrq3000/Utterlane", destination?.dataString)
            } finally { instrumentation.removeMonitor(link) }
            ui.scrollToText("All speech processing happens on your device. Internet access is only needed for model downloads and external links.")
            ui.screenshot("settings-about")
            back()
            ui.node("settings_page_root").recycle()
        } finally { finish(activity) }
    }

    @Test fun fileWarningOpensSpeakerModelControlsAndBackPreservesTheFileChoice(): Unit = runBlocking {
        ui.prepare()
        val count = app.settingsRepository.speakerCount.first()
        assertFalse("Isolated test identity must not have a speaker model", app.diarizationModels.isModelReady())
        val source = File.createTempFile("speaker-settings-", ".wav", app.cacheDir)
        val imports = app.recordingHistory.list().map { it.id }
        var activity: Activity? = null
        var speakerSettings: Activity? = null
        val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        try {
            app.settingsRepository.setSpeakerCount(0)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
            activity = launchNativeActivity(Intent(Intent.ACTION_SEND).setPackage(app.packageName).setType("audio/wav")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            ui.click("file_setup_speakers")
            ui.awaitChecked("file_setup_speakers", true)
            ui.scrollTo("open_speaker_settings")
            ui.click("open_speaker_settings", scroll = false)
            speakerSettings = checkNotNull(monitor.waitForActivityWithTimeout(5000))
            ui.node("settings_page_speakers").recycle()
            ui.scrollTo("speaker_model_download")
            ui.scrollTo("speaker_model_import")
            assertFalse("A recovery shortcut must not expose Home tabs that could discard the file", ui.hasNode("home_navigation"))
            assertFalse(app.diarizationModels.isTransferring)
            back()
            ui.awaitChecked("file_setup_speakers", true)
            assertEquals(imports, app.recordingHistory.list().map { it.id })
        } finally {
            finish(speakerSettings); finish(activity)
            instrumentation.removeMonitor(monitor)
            app.settingsRepository.setSpeakerCount(count)
            source.delete()
        }
    }

    @Test fun refreshingSpeakerModelStatusDoesNotMakeRejectedWeightsLookReady(): Unit = runBlocking {
        val definition = ModelDefinition("qa-speaker-${System.nanoTime()}", "Test speaker model", ModelBackend.CRISP,
            listOf(ModelArtifact("https://example.invalid/model.gguf", "model.gguf", 4, "0".repeat(64))))
        val manager = ModelManager(app, fixedModel = definition)
        val folder = manager.directory()
        try {
            folder.mkdirs()
            File(folder, "model.gguf").writeText("bad!")
            assertFalse(manager.ensureVerified())
            manager.refreshModelStatus()
            val error = manager.downloadState.value as ModelManager.DownloadState.Error
            assertEquals(ModelManager.ErrorType.CHECKSUM_MISMATCH, error.type)
        } finally { folder.deleteRecursively() }
    }

    @Test fun legacySpeechRecoveryStillOpensModelSelection(): Unit = runBlocking {
        ui.prepare()
        val activity = launchNativeActivity(RecordingRecovery.modelIntent(app))
        try {
            ui.textNode(activity.getString(R.string.overlay_cancel)).recycle()
            back()
            ui.node("settings_page_speech").recycle()
            back()
            kotlinx.coroutines.withTimeout(5000) {
                while (!activity.isFinishing && !activity.isDestroyed) kotlinx.coroutines.delay(30)
            }
        } finally { finish(activity) }
    }

    private fun back() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    private fun finish(activity: Activity?) = instrumentation.runOnMainSync { activity?.finish() }
}
