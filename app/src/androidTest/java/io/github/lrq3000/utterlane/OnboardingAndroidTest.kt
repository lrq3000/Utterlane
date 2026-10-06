package io.github.lrq3000.utterlane

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.lrq3000.utterlane.onboarding.*
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class OnboardingAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Before fun inspectInteractiveWindows() = ui.prepare()

    @Test fun progressSurvivesNewRepositoryAndReplayKeepsCompletion() = runBlocking {
        val repository = OnboardingRepository(app)
        val original = repository.progress.first()
        try {
            repository.update { OnboardingProgress() }
            assertTrue(repository.prepareAutomaticLaunch(false))
            repository.update { it.copy(stepId = "speakers", modelId = "chosen", speakersWanted = true) }
            val recreated = OnboardingRepository(app)
            assertEquals("speakers", recreated.progress.first().stepId)
            assertTrue(recreated.prepareAutomaticLaunch(true))
            recreated.complete()
            recreated.begin(true, "current", false, false)
            assertTrue(recreated.progress.first().completed)
            assertEquals("welcome", recreated.progress.first().stepId)
            assertFalse(recreated.prepareAutomaticLaunch(false))
        } finally { repository.update { original } }
    }

    @Test fun bundledPublicDomainSampleIsReadableAndShareableWithoutLibraryAccess() = runBlocking {
        val intent = OnboardingSample(app).shareIntent()
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(288044, bytes.size)
        assertEquals("7382d35e88949640179e233a32157d9fe85907e0342f6a22bd08ccef26e293f5",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        assertEquals("content", uri.scheme)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val receivers = app.packageManager.queryIntentActivities(intent, 0)
        assertTrue(receivers.any { it.activityInfo.name.endsWith(".TranscribeActivity") && it.activityInfo.packageName == app.packageName })
    }

    @Test fun everyRealPageHasAppearanceAndThemeChoicePersists() = runBlocking {
        val repository = OnboardingRepository(app)
        val original = repository.progress.first()
        val originalTheme = app.settingsRepository.themeMode.first()
        var activity: OnboardingActivity? = null
        try {
            repository.update { OnboardingProgress(initialized = true, speakersWanted = true) }
            activity = instrumentation.startActivitySync(Intent(app, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OnboardingActivity
            waitForNode("onboarding_appearance")
            for (step in OnboardingStep.entries) {
                repository.update { it.copy(stepId = step.id, speakersWanted = true) }
                waitForNode("onboarding_page_${step.id}")
                waitForNode("onboarding_appearance")
            }
            click("onboarding_appearance")
            click("onboarding_theme_dark")
            withTimeout(5000) { app.settingsRepository.themeMode.first { it == "dark" } }
            repository.update { it.copy(stepId = OnboardingStep.WELCOME.id) }
            waitForNode("onboarding_page_welcome")
            assertEquals("dark", app.settingsRepository.themeMode.first())
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            repository.update { original }
            app.settingsRepository.setThemeMode(originalTheme)
        }
    }

    @Test fun launcherResumesIncompleteSetupAndCompletionAllowsSettingsAndReplay() = runBlocking {
        val repository = OnboardingRepository(app)
        val original = repository.progress.first()
        try {
            repository.update { OnboardingProgress(initialized = true, stepId = OnboardingStep.USES.id) }
            instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForNode("onboarding_page_uses").recycle()
            repository.update { it.copy(stepId = OnboardingStep.COMPLETE.id) }
            waitForNode("onboarding_page_finish").recycle()
            click("onboarding_next")
            withTimeout(5000) { repository.progress.first { it.completed } }
            waitForResumed(SettingsActivity::class.java)
            instrumentation.startActivitySync(Intent(app, OnboardingActivity::class.java)
                .putExtra(OnboardingActivity.EXTRA_REPLAY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForNode("onboarding_page_welcome").recycle()
            assertTrue(repository.progress.first().completed)
        } finally {
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                (monitor.getActivitiesInStage(Stage.RESUMED) + monitor.getActivitiesInStage(Stage.STOPPED)).toList()
                    .filter { it is OnboardingActivity || it is SettingsActivity }.forEach { it.finish() }
            }
            repository.update { original }
        }
    }

    private fun waitForResumed(type: Class<*>) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var resumed = false
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { type.isInstance(it) }
            }
            if (resumed) return
            Thread.sleep(100)
        }
        throw AssertionError("Activity did not resume: ${type.simpleName}")
    }

    private fun click(id: String) = ui.click(id)
    private fun waitForNode(id: String): AccessibilityNodeInfo = ui.node(id)
}
