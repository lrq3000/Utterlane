package io.github.lrq3000.utterlane

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.settings.SettingsActivity
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
        val activity = instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java)
            .putExtra(RecordingRecovery.EXTRA_RECOVERY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            app.settingsRepository.setAudioHistoryRetention(HistoryRetention.NONE)
            ui.click("history_pin_${audio.entry.id}")
            withTimeout(5000) { while (!app.recordingHistory.get(audio.entry.id).pinned) delay(10) }
            ui.textNode(app.getString(R.string.history_pinned)).recycle()
            ui.click("history_pin_${audio.entry.id}")
            withTimeout(5000) { while (app.recordingHistory.get(audio.entry.id).pinned) delay(10) }
            app.historyCleanup.request(HistoryCleanupCoordinator.AUDIO)
            assertTrue(audio.entry.directory.exists())
            assertEquals(app.historyCleanup.launchToken, app.recordingHistory.get(audio.entry.id).holdForLaunch)
            ui.textNode(app.getString(R.string.history_unpin_immediate)).recycle()
            ui.screenshot("history-audio-unpin-grace")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            app.recordingHistory.delete(audio.entry.id)
            app.settingsRepository.setAudioHistoryRetention(old)
        }
    }

    @Test fun transcriptsHaveTheirOwnPinControl() = runBlocking {
        ui.prepare()
        val file = File.createTempFile("history-pin-", ".txt", app.cacheDir).apply { writeText("Independent transcript fixture") }
        val text = app.transcriptHistory.save(file, "QA model")
        val activity = instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java)
            .putExtra(RecordingRecovery.EXTRA_RECOVERY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            // Close the audio browser, then open the independent text browser.
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.clickText(app.getString(R.string.transcript_history_title))
            ui.click("history_pin_${text.id}")
            withTimeout(5000) { while (!app.transcriptHistory.get(text.id).retention.pinned) delay(10) }
            app.transcriptHistory.prune(HistoryRetention.NONE)
            assertTrue(text.file.exists())
            ui.textNode(app.getString(R.string.history_pinned)).recycle()
            ui.screenshot("history-transcript-pinned")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            app.transcriptHistory.delete(text.id); file.delete()
        }
    }
}
