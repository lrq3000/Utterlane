package io.github.lrq3000.utterlane

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.HistoryActivity
import io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeDestination
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryNavigationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun rootBackReturnsToRecordRatherThanSettings() = verifyHomeNavigation(rootBack = true)
    @Test fun legacyAudioCanNavigateToTheHomeTranscriptTab() = verifyHomeNavigation(rootBack = false)

    private fun verifyHomeNavigation(rootBack: Boolean) = runBlocking {
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
            if (rootBack) ui.click("home_back") else ui.click("home_nav_transcripts")
            home = monitor.waitForActivityWithTimeout(8000)
            assertNotNull(home)
            assertTrue(home!!.intent.getBooleanExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, false))
            assertEquals((if (rootBack) HomeDestination.RECORD else HomeDestination.TRANSCRIPTS).name,
                home!!.intent.getStringExtra(HomeActivity.EXTRA_DESTINATION))
            ui.node(if (rootBack) "home_screen" else "history_screen").recycle()
            assertTrue(history.isFinishing || history.isDestroyed)
            assertEquals("Legacy navigation must not advance Immediate retention", launch, app.historyCleanup.launchToken)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { home?.finish(); history.finish() }
            onboarding.update { previous }
        }
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
