package io.github.lrq3000.utterlane

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.DateFormat
import java.util.Date

@RunWith(AndroidJUnit4::class)
class HistoryPresentationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun failedAudioKeepsItsRecoveryIndicatorAndGenericTypeLabel() {
        ui.prepare()
        val recording = app.recordingHistory.begin(HistoryRetention.FOREVER)
        recording.append(ShortArray(24000)); recording.finish(true)
        app.recordingHistory.setSpeakerLabels(recording.entry.id, true)
        val history = instrumentation.startActivitySync(HistoryActivity.intent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val historyUi = HistoryTestUi(ui)
            val text = historyUi.text(recording.entry.id)
            assertTrue(text.contains(app.getString(R.string.history_audio_recording)))
            assertTrue(text.contains(app.getString(R.string.home_speakers)))
            assertTrue(historyUi.contentDescriptions(recording.entry.id)
                .contains(app.getString(R.string.history_recovered_entry)))
            ui.node("history_legend").recycle()
            ui.textNode(app.getString(R.string.history_pinned)).recycle()
            ui.screenshot("polish-audio-history-legend")
            historyUi.awaitPinned(recording.entry.id, false)
            historyUi.assertNavigationOnly(recording.entry.id)
        } finally {
            instrumentation.runOnMainSync { history.finish() }
            app.recordingHistory.delete(recording.entry.id)
        }
    }

    @Test fun firstLineAndPersistedMetadataReplaceTitlesAndCurrentSpeakerSetting() = runBlocking {
        ui.prepare()
        val previous = app.settingsRepository.diarizationEnabled.first()
        val source = File.createTempFile("history-presentation-", ".txt", app.cacheDir)
        val created = System.currentTimeMillis()
        source.writeText("\n  \r\n Speaker 1: Recorded words\nSecond line must stay in detail")
        val labeled = app.transcriptHistory.save(source, "Not a row title", pinned = true,
            created = created, durationMs = 128_000, speakerLabels = true)
        source.writeText("Legacy plain words")
        val legacy = app.transcriptHistory.save(source, "Not a row title", pinned = true, attempt = source.name + "-legacy")
        val history = instrumentation.startActivitySync(HistoryActivity.intent(app, transcripts = true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            app.settingsRepository.setDiarizationEnabled(false)
            val historyUi = HistoryTestUi(ui)
            val text = historyUi.text(labeled.id)
            assertTrue(text.contains("Speaker 1: Recorded words"))
            assertFalse(text.contains("Second line"))
            assertFalse(text.contains("Not a row title"))
            val locale = history.resources.configuration.locales[0]
            val date = app.getString(R.string.history_today)
            val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(created))
            val duration = String.format(locale, "%d:%02d", 2, 8)
            val speakers = app.getString(R.string.home_speakers)
            assertTrue(text.indexOf(date) >= 0)
            assertTrue(text.indexOf(time) > text.indexOf(date))
            assertTrue(text.indexOf(duration) > text.indexOf(time))
            assertTrue(text.indexOf(speakers) > text.indexOf(duration))
            historyUi.awaitPinned(labeled.id, true)
            historyUi.assertNavigationOnly(labeled.id)
            ui.node("history_legend").recycle()
            ui.textNode(app.getString(R.string.history_recovered_entry)).recycle()
            ui.screenshot("polish-transcript-history-legend")
            app.settingsRepository.setDiarizationEnabled(true)
            val oldText = historyUi.text(legacy.id)
            assertTrue("Unknown duration must not become zero seconds", oldText.contains("—"))
            assertFalse("Current settings cannot invent old speaker labels", oldText.contains(speakers))
            assertFalse(ui.hasVisibleText(app.getString(R.string.home_load_audio)))
        } finally {
            instrumentation.runOnMainSync { history.finish() }
            app.transcriptHistory.delete(labeled.id); app.transcriptHistory.delete(legacy.id)
            source.delete()
            app.settingsRepository.setDiarizationEnabled(previous)
        }
    }

    @Test fun payloadReadErrorOffersRetryAndRecoversTheSameEntry() {
        ui.prepare()
        val source = File.createTempFile("history-retry-", ".txt", app.cacheDir).apply { writeText("Recovered preview") }
        val entry = app.transcriptHistory.save(source, "QA", pinned = true)
        // Keep the index but make only this fixture's payload unavailable. The
        // failed IO must become a retry state rather than a misleading empty list.
        assertTrue(entry.file.delete())
        val history = instrumentation.startActivitySync(HistoryActivity.intent(app, transcripts = true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ui.textNode(app.getString(R.string.history_retry)).recycle()
            assertFalse(ui.hasVisibleText(app.getString(R.string.transcript_history_empty)))
            source.copyTo(entry.file)
            ui.clickText(app.getString(R.string.history_retry))
            assertTrue(HistoryTestUi(ui).text(entry.id).contains("Recovered preview"))
        } finally {
            instrumentation.runOnMainSync { history.finish() }
            app.transcriptHistory.delete(entry.id); source.delete()
        }
    }
}
