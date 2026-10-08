package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HistoryListInteractionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun recordingListRequiresOpeningBeforeDeletionAndPinDoesNotNavigate() = runBlocking { verify(transcripts = false) }
    @Test fun transcriptListRequiresOpeningBeforeDeletionAndPinDoesNotNavigate() = runBlocking { verify(transcripts = true) }

    private suspend fun verify(transcripts: Boolean) {
        ui.prepare()
        val audio = app.recordingHistory.begin(HistoryRetention.NONE)
        audio.append(ShortArray(3200)); audio.finish(true)
        app.recordingHistory.setPinned(audio.entry.id, true, HistoryRetention.DAY, app.historyCleanup.launchToken)
        val textFile = File.createTempFile("list-interaction-", ".txt", app.cacheDir).apply { writeText("Readable transcript preview for the compact history list.") }
        val text = app.transcriptHistory.save(textFile, "QA model", pinned = true)
        val id = if (transcripts) text.id else audio.entry.id
        val deletion = app.getString(if (transcripts) R.string.dialog_delete_text else R.string.history_delete)
        val activity = instrumentation.startActivitySync(HistoryActivity.intent(app, transcripts)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val monitor = instrumentation.addMonitor(TranscribeActivity::class.java.name, null, false)
        var detail: Activity? = null
        try {
            ui.node("history_pin_$id").recycle()
            assertFalse("Deletion must not be exposed by the history listing", ui.hasVisibleText(deletion))
            assertFalse("Opening is the whole entry's action, not another button", ui.hasVisibleText(app.getString(R.string.history_open)))
            assertFalse("Pin state must not consume another text row", ui.hasVisibleText(app.getString(R.string.history_pinned)))
            ui.click("history_pin_$id")
            withTimeout(5000) {
                while (if (transcripts) app.transcriptHistory.get(id).retention.pinned else app.recordingHistory.get(id).pinned) delay(10)
            }
            instrumentation.waitForIdleSync()
            assertEquals("The pin must consume the tap without opening the row", 0, monitor.hits)
            ui.click("history_entry_$id")
            detail = monitor.waitForActivityWithTimeout(5000)
            assertNotNull("The entry itself must open the shared dialog", detail)
            ui.textNode(deletion).recycle()
        } finally {
            instrumentation.removeMonitor(monitor)
            detail?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            instrumentation.runOnMainSync { activity.finish() }
            app.recordingHistory.delete(audio.entry.id)
            app.transcriptHistory.delete(text.id)
            textFile.delete()
        }
    }
}
