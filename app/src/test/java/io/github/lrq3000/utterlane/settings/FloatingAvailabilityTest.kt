package io.github.lrq3000.utterlane.settings

import android.Manifest
import android.app.Application
import android.os.Looper
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.ModelManager
import io.github.lrq3000.utterlane.service.FloatingMicService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class FloatingAvailabilityTest {
    private val repository = SettingsRepository(MemoryStore())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: UtterlaneApp

    @Before fun prepare() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        app = mockk<UtterlaneApp>(relaxed = true)
        val model = ModelManager(context)
        every { app.applicationContext } returns app
        every { app.checkPermission(any(), any(), any()) } answers {
            context.checkPermission(firstArg(), secondArg(), thirdArg())
        }
        every { app.startService(any()) } answers { context.startService(firstArg()) }
        every { app.startForegroundService(any()) } answers { context.startForegroundService(firstArg()) }
        every { app.settingsRepository } returns repository
        every { app.applicationScope } returns scope
        every { app.modelManager } returns model
        assertFalse(model.isModelReady())
        ReflectionHelpers.setStaticField(UtterlaneApp::class.java, "instance", app)
        repository.setServiceEnabled(true)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After fun cleanup() { scope.cancel() }

    @Test fun returningToSettingsWithoutAModelKeepsFloatingCaptureEnabled() = runBlocking {
        resumeSettings()
        assertTrue("A missing model is not a missing microphone permission", repository.serviceEnabled.first())
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(FloatingMicService::class.java.name, intent.component?.className)
        assertNull(intent.action)
    }

    @Test fun missingMicrophonePermissionStillDisablesTheService() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        resumeSettings()
        assertFalse(repository.serviceEnabled.first())
        assertEquals(FloatingMicService.ACTION_STOP, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService.action)
    }

    @Test fun missingOverlayPermissionStillDisablesTheService() = runBlocking {
        ShadowSettings.setCanDrawOverlays(false)
        resumeSettings()
        assertFalse(repository.serviceEnabled.first())
    }

    private fun resumeSettings() {
        // Home and Settings now share this actual entry/permission policy. Keep
        // real Android permission and service dispatch without constructing UI.
        AppEntryServices.restore(app)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
