package io.github.lrq3000.utterlane

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.RecordingRecovery
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingSettingsAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun visualFrequencyCanBeChangedInAppearanceAndSurvivesReopening() = runBlocking {
        ui.prepare()
        val previous = app.settingsRepository.visualRefreshRate.first()
        var activity: SettingsActivity? = null
        try {
            app.settingsRepository.setVisualRefreshRate(10)
            activity = open(RecordingRecovery.EXTRA_MODELS)
            ui.clickText(app.getString(R.string.visual_refresh_title))
            ui.clickText(app.getString(R.string.visual_refresh_rate, 1))
            withTimeout(5000) { while (app.settingsRepository.visualRefreshRate.first() != 1) delay(20) }
            val previousActivity = activity!!
            instrumentation.runOnMainSync { previousActivity.finish() }
            // The previous window can remain accessible during its closing
            // animation. Do not click that old settings row after reopening.
            withTimeout(5000) { while (!previousActivity.isDestroyed) delay(20) }
            instrumentation.waitForIdleSync()
            activity = open(RecordingRecovery.EXTRA_MODELS)
            ui.clickText(app.getString(R.string.visual_refresh_title))
            val label = ui.textNode(app.getString(R.string.visual_refresh_rate, 1))
            @Suppress("DEPRECATION") label.recycle()
            assertEquals(1, app.settingsRepository.visualRefreshRate.first())
            ui.screenshot("recording-visual-refresh-1hz")
        } finally {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            app.settingsRepository.setVisualRefreshRate(previous)
        }
    }

    @Test fun recoveryRemainsVisibleWithHistoryOffAndOffersModelSelection() = runBlocking {
        ui.prepare()
        val previous = app.settingsRepository.historyRetention.first()
        val recording = app.recordingHistory.begin(HistoryRetention.NONE)
        recording.append(ShortArray(1600) { 1000 })
        recording.finish(true)
        var activity: SettingsActivity? = null
        try {
            app.settingsRepository.setHistoryRetention(HistoryRetention.NONE)
            app.recordingHistory.prune(HistoryRetention.NONE)
            activity = open(RecordingRecovery.EXTRA_RECOVERY)
            val pending = ui.textNode(app.getString(R.string.recording_recovery_pending))
            @Suppress("DEPRECATION") pending.recycle()
            val choose = ui.textNode(app.getString(R.string.recording_choose_model))
            @Suppress("DEPRECATION") choose.recycle()
            ui.screenshot("recording-unfinished-recovery")
            assertTrue(recording.entry.directory.exists())
        } finally {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            app.recordingHistory.delete(recording.entry.id)
            app.settingsRepository.setHistoryRetention(previous)
        }
    }

    private fun open(extra: String): SettingsActivity {
        val activity = instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java)
            .putExtra(extra, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        instrumentation.waitForIdleSync()
        return activity
    }
}
