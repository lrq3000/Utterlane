package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.HistoryActivity
import io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.RecordingRecovery
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeDestination
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class HistoryNavigationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun rootBackReturnsToRecordRatherThanSettings() = verifyRootBack(fromRecord = false)
    @Test fun rootRecordBackAlsoReturnsToHome() = verifyRootBack(fromRecord = true)

    private fun verifyRootBack(fromRecord: Boolean) = runBlocking {
        ui.prepare()
        val onboarding = OnboardingRepository(app)
        val previous = onboarding.progress.first()
        onboarding.complete()
        // A separate task models a notification/deep entry with no parent; never
        // clear an existing task just to manufacture the root case.
        val history = instrumentation.startActivitySync(HistoryActivity.intent(app)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        val monitor = instrumentation.addMonitor(HomeActivity::class.java.name, null, false)
        var home: Activity? = null
        try {
            assertTrue(history.isTaskRoot)
            val launch = app.historyCleanup.launchToken
            ui.node("home_navigation").recycle()
            ui.click("home_nav_audio")
            instrumentation.waitForIdleSync()
            assertEquals("Selecting the current primary destination is a no-op", 0, monitor.hits)
            assertFalse(history.isFinishing)
            if (fromRecord) {
                ui.click("home_nav_record")
                ui.node("home_screen").recycle()
                assertFalse("Record must remain in the legacy host until Back", history.isFinishing)
                assertEquals(0, monitor.hits)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            } else ui.click("home_back")
            home = monitor.waitForActivityWithTimeout(8000)
            assertNotNull(home)
            assertTrue(home!!.intent.getBooleanExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, false))
            assertEquals(HomeDestination.RECORD.name,
                home!!.intent.getStringExtra(HomeActivity.EXTRA_DESTINATION))
            ui.node("home_screen").recycle()
            assertTrue(history.isFinishing || history.isDestroyed)
            assertEquals("Legacy navigation must not advance Immediate retention", launch, app.historyCleanup.launchToken)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { home?.finish(); history.finish() }
            onboarding.update { previous }
        }
    }

    @Test fun legacyAudioTextAndRecordRoundTripRetainsBothAnchorsAndSettingsParent() = runBlocking<Unit> {
        ui.prepare()
        val audioIds = mutableListOf<String>()
        val textIds = mutableListOf<String>()
        val source = File.createTempFile("legacy-history-tabs-", ".txt", app.cacheDir)
        var settings: Activity? = null
        var history: Activity? = null
        val historyMonitor = instrumentation.addMonitor(HistoryActivity::class.java.name, null, false)
        val homeMonitor = instrumentation.addMonitor(HomeActivity::class.java.name, null, false)
        try {
            repeat(120) { index ->
                val audio = app.recordingHistory.begin(HistoryRetention.FOREVER)
                audio.append(ShortArray(160)); audio.finish(false)
                app.recordingHistory.setPinned(audio.entry.id, true, HistoryRetention.FOREVER, app.historyCleanup.launchToken)
                audioIds.add(audio.entry.id)
                source.writeText("Legacy tab fixture $index")
                textIds.add(app.transcriptHistory.save(source, "QA", pinned = true, attempt = "${source.name}-$index").id)
            }
            settings = instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java)
                .putExtra(RecordingRecovery.EXTRA_MODELS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui.textNode(app.getString(R.string.model_choose)).recycle()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            val onboarding = OnboardingRepository(app).progress.first()
            val launch = app.historyCleanup.launchToken
            ui.scrollTo("settings_audio_history")
            ui.click("settings_audio_history")
            history = historyMonitor.waitForActivityWithTimeout(8000)
            assertNotNull(history)
            assertFalse(history!!.isTaskRoot)

            // Both targets lie past the 90-entry window, so replacing the host
            // cannot accidentally pass by showing the same first page again.
            val audioTarget = audioIds[10]
            val textTarget = textIds[10]
            ui.scrollTo("history_entry_$audioTarget")
            val audioBounds = rowBounds(audioTarget)
            ui.click("home_nav_transcripts")
            ui.scrollTo("history_entry_$textTarget")
            val textBounds = rowBounds(textTarget)
            ui.click("home_nav_audio")
            assertPosition(audioBounds, audioTarget)
            ui.click("home_nav_record")
            ui.node("home_screen").recycle()
            ui.click("home_nav_transcripts")
            assertPosition(textBounds, textTarget)
            ui.click("home_nav_record")
            ui.node("home_screen").recycle()

            // Record is a real destination in the same saved-state host, not a
            // handoff that discards either history when the Activity is recreated.
            val old = history!!
            // The launch monitor can still contain the old Activity's later
            // onResume notification. Observe recreation with its own scoped queue.
            val recreation = instrumentation.addMonitor(HistoryActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { old.recreate() }
                history = recreation.waitForActivityWithTimeout(8000)
                assertNotNull(history)
                assertNotSame(old, history)
                withTimeout(5000) { while (!old.isDestroyed) delay(20) }
            } finally { instrumentation.removeMonitor(recreation) }
            ui.node("home_screen").recycle()
            ui.click("home_nav_audio")
            assertPosition(audioBounds, audioTarget)
            ui.click("home_nav_transcripts")
            assertPosition(textBounds, textTarget)
            assertEquals("Tab selection must not create a different Home host", 0, homeMonitor.hits)
            assertEquals(onboarding, OnboardingRepository(app).progress.first())
            assertEquals(launch, app.historyCleanup.launchToken)
            ui.click("home_back")
            instrumentation.waitForIdleSync()
            assertTrue(history!!.isFinishing || history!!.isDestroyed)
            assertFalse("Back must return to the original Settings parent", settings!!.isFinishing)
            ui.textNode(app.getString(R.string.history_title)).recycle()
        } finally {
            instrumentation.removeMonitor(historyMonitor)
            instrumentation.removeMonitor(homeMonitor)
            instrumentation.runOnMainSync { history?.finish(); settings?.finish() }
            audioIds.forEach(app.recordingHistory::delete)
            textIds.forEach(app.transcriptHistory::delete)
            source.delete()
        }
    }

    @Test fun legacyEntryDoesNotInitializeOrRedirectOnboarding() = runBlocking {
        ui.prepare()
        val onboarding = OnboardingRepository(app)
        val previous = onboarding.progress.first()
        val pending = previous.copy(initialized = false, completed = false)
        var history: Activity? = null
        try {
            onboarding.update { pending }
            val launch = app.historyCleanup.launchToken
            history = instrumentation.startActivitySync(HistoryActivity.intent(app, transcripts = true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui.node("history_screen").recycle()
            ui.click("home_nav_record")
            ui.node("home_screen").recycle()
            ui.click("home_nav_transcripts")
            ui.node("history_screen").recycle()
            assertFalse(history!!.isFinishing)
            assertEquals(pending, onboarding.progress.first())
            assertEquals(launch, app.historyCleanup.launchToken)
        } finally {
            instrumentation.runOnMainSync { history?.finish() }
            onboarding.update { previous }
        }
    }

    private fun rowBounds(id: String): Rect = HistoryTestUi(ui).settledBounds(id)

    private fun assertPosition(before: Rect, id: String) {
        // Match the existing paging regression's 2px accessibility-rounding tolerance.
        val after = rowBounds(id)
        assertEquals(before.left, after.left)
        assertEquals(before.right, after.right)
        assertTrue("History anchor moved: $before -> $after", abs(before.top - after.top) <= 2 && abs(before.bottom - after.bottom) <= 2)
    }

    @Test fun gearOpensSettingsInternallyAndBackReturnsToHistory() {
        ui.prepare()
        val history = instrumentation.startActivitySync(HistoryActivity.intent(app)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        var settings: Activity? = null
        try {
            val launch = app.historyCleanup.launchToken
            ui.click("home_settings")
            settings = monitor.waitForActivityWithTimeout(8000)
            assertNotNull(settings)
            assertTrue(settings!!.intent.getBooleanExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, false))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.node("history_screen").recycle()
            assertFalse(history.isFinishing)
            assertEquals(launch, app.historyCleanup.launchToken)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { settings?.finish(); history.finish() }
        }
    }
}
