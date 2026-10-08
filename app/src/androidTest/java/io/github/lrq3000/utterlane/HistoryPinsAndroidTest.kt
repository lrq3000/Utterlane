package io.github.lrq3000.utterlane

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HistoryPinsAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun onlyANewUserEntryReleasesImmediateUnpinGrace() = runBlocking {
        val old = app.settingsRepository.audioHistoryRetention.first()
        instrumentation.runOnMainSync { app.historyCleanup.userEntry(Intent(Intent.ACTION_MAIN), null) }
        val audio = app.recordingHistory.begin(HistoryRetention.NONE)
        audio.append(ShortArray(1600)); audio.finish(true)
        try {
            app.settingsRepository.setAudioHistoryRetention(HistoryRetention.NONE)
            val launch = app.historyCleanup.launchToken
            app.recordingHistory.setPinned(audio.entry.id, true, HistoryRetention.NONE, launch)
            app.recordingHistory.setPinned(audio.entry.id, false, HistoryRetention.NONE, launch)
            instrumentation.runOnMainSync {
                app.historyCleanup.userEntry(Intent().putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true), null)
                app.historyCleanup.userEntry(Intent(Intent.ACTION_MAIN), android.os.Bundle())
            }
            app.historyCleanup.request(HistoryCleanupCoordinator.AUDIO)
            assertEquals(launch, app.historyCleanup.launchToken)
            assertTrue(audio.entry.directory.exists())
            instrumentation.runOnMainSync { app.historyCleanup.userEntry(Intent(Intent.ACTION_MAIN), null) }
            withTimeout(5000) { while (audio.entry.directory.exists()) delay(10) }
            assertNotEquals(launch, app.historyCleanup.launchToken)
        } finally { app.recordingHistory.delete(audio.entry.id); app.settingsRepository.setAudioHistoryRetention(old) }
    }

    @Test fun audioPinChangesRetentionAndImmediateUnpinSurvivesBackgroundCleanup() = runBlocking {
        ui.prepare()
        val old = app.settingsRepository.audioHistoryRetention.first()
        val audio = app.recordingHistory.begin(HistoryRetention.NONE)
        audio.append(ShortArray(1600)); audio.finish(true)
        val examples = listOf(2, 9, 36).map(::savedRecording)
        val originalOrder = app.recordingHistory.list().map { it.id }
        val activity = instrumentation.startActivitySync(HistoryActivity.intent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            app.settingsRepository.setAudioHistoryRetention(HistoryRetention.NONE)
            ui.click("history_pin_${audio.entry.id}")
            withTimeout(5000) { while (!app.recordingHistory.get(audio.entry.id).pinned) delay(10) }
            ui.awaitChecked("history_pin_${audio.entry.id}", true)
            ui.click("history_pin_${audio.entry.id}")
            withTimeout(5000) { while (app.recordingHistory.get(audio.entry.id).pinned) delay(10) }
            app.historyCleanup.request(HistoryCleanupCoordinator.AUDIO)
            assertTrue(audio.entry.directory.exists())
            assertEquals(app.historyCleanup.launchToken, app.recordingHistory.get(audio.entry.id).holdForLaunch)
            assertEquals("Pinning must not move entries under the user's finger", originalOrder, app.recordingHistory.list().map { it.id })
            ui.awaitChecked("history_pin_${audio.entry.id}", false)
            assertFalse(ui.hasVisibleText(app.getString(R.string.history_unpin_immediate)))
            ui.screenshot("history-audio-unpin-grace")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            app.recordingHistory.delete(audio.entry.id)
            examples.forEach { app.recordingHistory.delete(it.id) }
            app.settingsRepository.setAudioHistoryRetention(old)
        }
    }

    @Test fun transcriptsHaveTheirOwnPinControl() = runBlocking {
        ui.prepare()
        val previousTheme = app.settingsRepository.themeMode.first()
        app.settingsRepository.setThemeMode(io.github.lrq3000.utterlane.settings.SettingsRepository.THEME_LIGHT)
        val file = File.createTempFile("history-pin-", ".txt", app.cacheDir).apply { writeText("Keep the audio only when needed. The transcript can stay on its own.") }
        val model = app.modelManager.selected.value.name
        val text = app.transcriptHistory.save(file, model)
        val examples = listOf(
            "We should confirm the delivery date before updating the schedule.",
            "The next step is to test the revised workflow on a slower phone.",
            "These are the observations we want to keep for the next review."
        ).map { content ->
            val source = File.createTempFile("history-list-", ".txt", app.cacheDir).apply { writeText(content) }
            try { app.transcriptHistory.save(source, model, pinned = true) } finally { source.delete() }
        }
        val activity = instrumentation.startActivitySync(HistoryActivity.intent(app, transcripts = true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            app.transcriptHistory.setPinned(examples[0].id, false, HistoryRetention.NONE, app.historyCleanup.launchToken)
            ui.click("history_pin_${text.id}")
            withTimeout(5000) { while (!app.transcriptHistory.get(text.id).retention.pinned) delay(10) }
            app.transcriptHistory.prune(HistoryRetention.NONE)
            assertTrue(text.file.exists())
            ui.awaitChecked("history_pin_${text.id}", true)
            assertFalse(ui.hasVisibleText(app.getString(R.string.history_pinned)))
            ui.screenshot("history-transcript-pinned")
            app.settingsRepository.setThemeMode(io.github.lrq3000.utterlane.settings.SettingsRepository.THEME_DARK)
            ui.screenshot("history-transcript-design-b-dark")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            app.transcriptHistory.delete(text.id); file.delete()
            examples.forEach { app.transcriptHistory.delete(it.id) }
            app.settingsRepository.setThemeMode(previousTheme)
        }
    }

    private fun savedRecording(seconds: Int): HistoryEntry {
        val recording = app.recordingHistory.begin(HistoryRetention.FOREVER)
        repeat(seconds * 5) { recording.append(ShortArray(3200)) }
        recording.finish(false)
        app.recordingHistory.setPinned(recording.entry.id, true, HistoryRetention.DAY, app.historyCleanup.launchToken)
        return app.recordingHistory.get(recording.entry.id)
    }
}
