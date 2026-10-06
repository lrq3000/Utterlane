package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.onboarding.AppOnboardingServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingFolderAndroidTest {
    @Test fun multipleUnavailableFoldersCanBeRemovedOneAtATime() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        val settings = app.settingsRepository
        val previousFolders = settings.monitoredFolders.first()
        val previousEnabled = settings.audioMonitorEnabled.first()
        val survivor = java.io.File(app.cacheDir, "monitor-survivor-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            settings.setMonitoredFolders(setOf(survivor.absolutePath, "/storage/removed/B", "/storage/removed/C"))
            settings.setAudioMonitorEnabled(true)
            val services = AppOnboardingServices(app)
            services.removeFolder("/storage/removed/B")
            assertEquals(setOf(survivor.absolutePath, "/storage/removed/C"), settings.monitoredFolders.first())
            services.removeFolder("/storage/removed/C")
            assertEquals(setOf(survivor.absolutePath), settings.monitoredFolders.first())
            assertTrue(settings.audioMonitorEnabled.first())
            assertFalse("Editing a stored preference must not start a stopped watcher", app.transcribeManager.isAudioMonitorActive())
        } finally {
            app.stopService(android.content.Intent(app, io.github.lrq3000.utterlane.transcribe.AudioMonitorService::class.java))
            settings.setMonitoredFolders(previousFolders)
            settings.setAudioMonitorEnabled(previousEnabled)
            survivor.delete()
        }
    }

    @Test fun failedNonemptyFolderChangePreservesPreviouslyEnabledConfiguration() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        val settings = app.settingsRepository
        val previousFolders = settings.monitoredFolders.first()
        val previousEnabled = settings.audioMonitorEnabled.first()
        val folders = setOf("/storage/emulated/0/Download/Voice", "/storage/removed/Recordings")
        try {
            settings.setMonitoredFolders(folders)
            settings.setAudioMonitorEnabled(true)
            try {
                AppOnboardingServices(app).addFolder("/storage/emulated/0/Download/Replacement")
                fail("The proposed set still contains an unavailable folder")
            } catch (_: IllegalStateException) { }
            assertEquals(folders, settings.monitoredFolders.first())
            assertTrue(settings.audioMonitorEnabled.first())
        } finally {
            settings.setMonitoredFolders(previousFolders)
            settings.setAudioMonitorEnabled(previousEnabled)
        }
    }

    @Test fun staleFolderCanBeRemovedWithoutDiscardingAReplacement() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        val settings = app.settingsRepository
        val previousFolders = settings.monitoredFolders.first()
        val previousEnabled = settings.audioMonitorEnabled.first()
        try {
            settings.setAudioMonitorEnabled(false)
            settings.setMonitoredFolders(setOf("/storage/removed/Recordings", "/storage/emulated/0/Download/Voice"))
            AppOnboardingServices(app).removeFolder("/storage/removed/Recordings")
            assertEquals(setOf("/storage/emulated/0/Download/Voice"), settings.monitoredFolders.first())
        } finally {
            settings.setMonitoredFolders(previousFolders)
            settings.setAudioMonitorEnabled(previousEnabled)
        }
    }

    @Test fun removingTheLastFolderDoesNotSilentlyFallBackToMonitoringDownloads() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        val settings = app.settingsRepository
        val previousFolders = settings.monitoredFolders.first()
        val previousEnabled = settings.audioMonitorEnabled.first()
        try {
            settings.setMonitoredFolders(setOf("/storage/removed/Recordings"))
            settings.setAudioMonitorEnabled(true)
            AppOnboardingServices(app).removeFolder("/storage/removed/Recordings")
            assertTrue(settings.monitoredFolders.first().isEmpty())
            assertFalse(settings.audioMonitorEnabled.first())
        } finally {
            settings.setMonitoredFolders(previousFolders)
            settings.setAudioMonitorEnabled(previousEnabled)
        }
    }
}
