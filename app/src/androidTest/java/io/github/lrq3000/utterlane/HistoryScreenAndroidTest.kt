package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.util.TypedValue
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.RecordingRecovery
import io.github.lrq3000.utterlane.history.HistoryActivity
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.settings.SettingsActivity
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class HistoryScreenAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun settingsOpensFullScreenAudioHistoryWithTitleAndBackNavigation() = verifyPage(false)
    @Test fun settingsOpensFullScreenTranscriptHistoryWithTitleAndBackNavigation() = verifyPage(true)
    @Test fun audioScrollsBeyondTheWindowAndRestoresAfterDetailsAndRecreation() = verifyLongHistory(false)
    @Test fun transcriptsScrollBeyondTheWindowAndRestoreInDarkTheme() = verifyLongHistory(true)

    private fun verifyLongHistory(transcripts: Boolean) = runBlocking {
        ui.prepare()
        val theme = app.settingsRepository.themeMode.first()
        val fixtures = mutableSetOf<String>()
        var history: Activity? = null
        var detail: Activity? = null
        val detailMonitor = instrumentation.addMonitor(TranscribeActivity::class.java.name, null, false)
        val source = File.createTempFile("history-page-", ".txt", app.cacheDir)
        try {
            app.settingsRepository.setThemeMode(if (transcripts) SettingsRepository.THEME_DARK else SettingsRepository.THEME_LIGHT)
            repeat(160) { index ->
                if (transcripts) {
                    source.writeText("Record ${index + 1}: Capture the important ideas, review them when needed, and keep every next step easy to find.")
                    fixtures.add(app.transcriptHistory.save(source, "QA recognition model", pinned = true,
                        attempt = "${source.name}-$index").id)
                } else {
                    val recording = app.recordingHistory.begin(HistoryRetention.FOREVER)
                    recording.append(ShortArray(1600)); recording.finish(false)
                    app.recordingHistory.setPinned(recording.entry.id, true, HistoryRetention.FOREVER, app.historyCleanup.launchToken)
                    fixtures.add(recording.entry.id)
                }
            }
            val ordered = (if (transcripts) app.transcriptHistory.list(0, 500).map { it.id }
                else app.recordingHistory.list(0, 500).map { it.id }).filter { it in fixtures }
            assertEquals(160, ordered.size)
            val launch = app.historyCleanup.launchToken
            history = instrumentation.startActivitySync(HistoryActivity.intent(app, transcripts).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui.node("history_entry_${ordered.first()}").recycle()
            ui.screenshot(if (transcripts) "fullscreen-transcript-records-dark" else "fullscreen-audio-records-light")

            // Reach beyond the 90-row paging window, forcing automatic append and
            // page eviction. Pin refresh must keep the older entry under the finger.
            val target = ordered[130]
            ui.scrollTo("history_entry_$target")
            val beforeRefresh = rowBounds(target)
            if (transcripts) app.transcriptHistory.setPinned(target, false, HistoryRetention.FOREVER, launch)
            else app.recordingHistory.setPinned(target, false, HistoryRetention.FOREVER, launch)
            HistoryTestUi(ui).awaitPinned(target, false)
            assertRowPosition(beforeRefresh, target)
            val before = rowBounds(target)
            ui.click("history_entry_$target")
            detail = detailMonitor.waitForActivityWithTimeout(5000)
            assertNotNull(detail)
            ui.textNode(app.getString(if (transcripts) R.string.dialog_delete_text else R.string.history_delete)).recycle()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            withTimeout(5000) { while (!detail!!.isDestroyed) delay(20) }
            assertRowPosition(before, target)

            val old = history!!
            val recreated = instrumentation.addMonitor(HistoryActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { old.recreate() }
                history = recreated.waitForActivityWithTimeout(8000)
                assertNotNull(history)
                assertNotSame(old, history)
                withTimeout(5000) { while (!old.isDestroyed) delay(20) }
                assertRowPosition(before, target)
            } finally { instrumentation.removeMonitor(recreated) }
            assertEquals("History/detail navigation is not a new retention launch", launch, app.historyCleanup.launchToken)
            ui.scrollTo("history_entry_${ordered.first()}", backwards = true)
            assertTrue(rowBounds(ordered.first()).height() > 0)
            assertFalse(ui.hasVisibleText(app.getString(R.string.stream_next)))
        } finally {
            instrumentation.removeMonitor(detailMonitor)
            instrumentation.runOnMainSync { detail?.finish(); history?.finish() }
            fixtures.forEach { if (transcripts) app.transcriptHistory.delete(it) else app.recordingHistory.delete(it) }
            source.delete()
            app.settingsRepository.setThemeMode(theme)
        }
    }

    private fun rowBounds(id: String): Rect {
        val node = ui.node("history_entry_$id")
        return try {
            assertTrue("The anchored row must still be visible", node.isVisibleToUser)
            Rect().also(node::getBoundsInScreen)
        } finally { node.recycle() }
    }

    private fun assertRowPosition(before: Rect, id: String) {
        val after = rowBounds(id)
        assertEquals(before.left, after.left); assertEquals(before.right, after.right)
        // Accessibility rounds fractional scroll/layout coordinates to integer
        // pixels. A 1 px difference was observed after closing the detail window;
        // the reproduced paging-window jump was an entire row (114 px or more).
        assertTrue("Browsing position changed from $before to $after",
            abs(before.top - after.top) <= 2 && abs(before.bottom - after.bottom) <= 2)
    }

    private fun verifyPage(transcripts: Boolean) {
        ui.prepare()
        val settings = openSettings()
        val monitor = instrumentation.addMonitor("io.github.lrq3000.utterlane.history.HistoryActivity", null, false)
        var history: Activity? = null
        try {
            val title = app.getString(if (transcripts) R.string.transcript_history_title else R.string.history_title)
            ui.clickText(title)
            history = monitor.waitForActivityWithTimeout(5000)
            assertNotNull("History must launch a real full-screen Activity, not a dialog", history)
            val floating = TypedValue()
            history!!.theme.resolveAttribute(android.R.attr.windowIsFloating, floating, true)
            assertEquals("History must use the normal application window theme", 0, floating.data)
            val bounds = Rect()
            ui.node("history_screen").let { node -> node.getBoundsInScreen(bounds); node.recycle() }
            val display = history.resources.displayMetrics
            assertTrue("The history must fill the available width", bounds.width() >= display.widthPixels * 0.95)
            // Persistent primary navigation now owns the bottom of the window.
            assertTrue("History must fill the content above navigation", bounds.height() >= display.heightPixels * 0.65)
            ui.node("home_navigation").recycle()
            ui.node("home_settings").recycle()
            ui.textNode(title).recycle()
            HistoryTestUi(ui).assertAbsent("brand_wordmark")
            HistoryTestUi(ui).assertAbsent("home_load_audio")
            assertFalse(ui.hasVisibleText(app.getString(R.string.stream_previous)))
            assertFalse(ui.hasVisibleText(app.getString(R.string.stream_next)))
            ui.screenshot(if (transcripts) "fullscreen-transcript-history" else "fullscreen-audio-history")
            ui.click("home_back")
            instrumentation.waitForIdleSync()
            assertTrue("Back returns to the original Settings Activity", history.isFinishing || history.isDestroyed)
            assertFalse(settings.isFinishing)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { history?.finish(); settings.finish() }
        }
    }

    private fun openSettings(): Activity {
        // An explicit existing entry avoids changing the user's onboarding state.
        val activity = instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java)
            .putExtra(RecordingRecovery.EXTRA_MODELS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.textNode(app.getString(R.string.model_choose)).recycle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        return activity
    }
}
