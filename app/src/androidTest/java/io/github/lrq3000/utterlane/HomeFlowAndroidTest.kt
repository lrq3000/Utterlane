package io.github.lrq3000.utterlane

import android.content.Intent
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.home.HomeActivity
import io.github.lrq3000.utterlane.home.HomeDestination
import io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import io.github.lrq3000.utterlane.onboarding.OnboardingRepository
import io.github.lrq3000.utterlane.history.HistoryRetention
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Parent runs these on its isolated native QA install; no browser substitute. */
@RunWith(AndroidJUnit4::class)
class HomeFlowAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun primaryTabsAndSettingsReturnToTheSameHomeWorkspace() = runBlocking {
        ui.prepare()
        val onboarding = OnboardingRepository(app)
        val previous = onboarding.progress.first()
        onboarding.complete()
        val home = instrumentation.startActivitySync(HomeActivity.intent(app))
        val settingsMonitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        val launch = app.historyCleanup.launchToken
        try {
            ui.node("home_screen").recycle()
            ui.click("home_nav_audio")
            ui.node("history_screen").recycle()
            ui.click("home_nav_transcripts")
            ui.node("history_screen").recycle()
            ui.click("home_nav_record")
            ui.node("home_load_audio").recycle()
            ui.click("home_settings")
            val settings = settingsMonitor.waitForActivityWithTimeout(5000)
            assertNotNull(settings)
            ui.click("home_nav_record")
            ui.node("home_screen").recycle()
            assertFalse(home.isDestroyed)
            assertEquals(launch, app.historyCleanup.launchToken)
        } finally {
            instrumentation.removeMonitor(settingsMonitor)
            instrumentation.runOnMainSync { home.finish() }
            onboarding.update { previous }
        }
    }

    @Test fun intentDestinationsAreExplicitAndInternalNavigationDoesNotAdvanceRetention() {
        val internal = HomeActivity.intent(app, HomeDestination.TRANSCRIPTS, internal = true)
        assertTrue(internal.getBooleanExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, false))
        assertEquals(HomeDestination.TRANSCRIPTS.name, internal.getStringExtra(HomeActivity.EXTRA_DESTINATION))
        assertTrue(internal.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(internal.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertFalse(HomeActivity.intent(app, internal = false)
            .getBooleanExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))
    }

    @Test fun eachHistoryRetainsItsOwnScrolledPositionAcrossTabsAndNewIntents() = runBlocking<Unit> {
        ui.prepare()
        val onboarding = OnboardingRepository(app)
        val previous = onboarding.progress.first()
        val audioIds = mutableListOf<String>()
        val textIds = mutableListOf<String>()
        val source = File.createTempFile("home-history-", ".txt", app.cacheDir)
        var home: android.app.Activity? = null
        try {
            onboarding.complete()
            repeat(100) { index ->
                val audio = app.recordingHistory.begin(HistoryRetention.FOREVER)
                audio.append(ShortArray(160)); audio.finish(false)
                app.recordingHistory.setPinned(audio.entry.id, true, HistoryRetention.FOREVER, app.historyCleanup.launchToken)
                audioIds.add(audio.entry.id)
                source.writeText("Home paging fixture $index")
                textIds.add(app.transcriptHistory.save(source, "Home QA", pinned = true, attempt = "${source.name}-$index").id)
            }
            home = instrumentation.startActivitySync(HomeActivity.intent(app, HomeDestination.AUDIO))
            val audioTarget = audioIds[25]
            ui.scrollTo("history_entry_$audioTarget")
            val audioPosition = rowBounds(audioTarget)
            ui.click("home_nav_transcripts")
            val textTarget = textIds[40]
            ui.scrollTo("history_entry_$textTarget")
            val textPosition = rowBounds(textTarget)
            ui.click("home_nav_record")
            ui.node("home_screen").recycle()
            // CLEAR_TOP + SINGLE_TOP must select the requested tab on the live
            // Home instance rather than replacing its retained pagers.
            instrumentation.runOnMainSync { home!!.startActivity(HomeActivity.intent(home!!, HomeDestination.AUDIO)) }
            assertPosition(audioPosition, rowBounds(audioTarget))
            ui.click("home_nav_transcripts")
            assertPosition(textPosition, rowBounds(textTarget))
        } finally {
            instrumentation.runOnMainSync { home?.finish() }
            audioIds.forEach(app.recordingHistory::delete)
            textIds.forEach(app.transcriptHistory::delete)
            source.delete()
            onboarding.update { previous }
        }
    }

    private fun rowBounds(id: String): Rect = ui.node("history_entry_$id").let { node ->
        try {
            assertTrue(node.isVisibleToUser)
            Rect().also(node::getBoundsInScreen)
        } finally { node.recycle() }
    }

    private fun assertPosition(before: Rect, after: Rect) {
        // The existing history tests allow 2px for accessibility's fractional
        // coordinate rounding; a lost paging anchor moves by whole rows.
        assertEquals(before.left, after.left)
        assertEquals(before.right, after.right)
        assertTrue("History anchor moved: $before -> $after", abs(before.top - after.top) <= 2 && abs(before.bottom - after.bottom) <= 2)
    }
}
