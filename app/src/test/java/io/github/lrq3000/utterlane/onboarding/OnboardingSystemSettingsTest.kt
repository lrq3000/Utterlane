package io.github.lrq3000.utterlane.onboarding

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import io.github.lrq3000.utterlane.service.TextInjectionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class OnboardingSystemSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun usesInstallingPackageRatherThanInitiatingPackage() {
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, "org.example.initiator", "com.android.vending")

        assertEquals("com.android.vending", app.onboardingInstallerPackage())
    }

    @Test fun missingInstallSourceDoesNotPreventOnboarding() {
        assertNull(app.onboardingInstallerPackage())
    }

    @Test fun unknownInstallerRemainsUnknownWhenTheInitiatorIsReported() {
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, "org.example.initiator", null)

        assertNull(app.onboardingInstallerPackage())
    }

    @Test @Config(sdk = [31])
    fun ignoresInstallerMetadataOnOlderAndroid() {
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, "com.android.vending", "com.android.vending")

        assertNull(app.onboardingInstallerPackage())
    }

    @Test fun configuredServiceIsRecognizedBeforeItConnects() {
        val services = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        Settings.Secure.putString(app.contentResolver, services, "org.example/org.example.OtherService")
        assertFalse(app.isOnboardingAccessibilityConfigured())

        val target = ComponentName(app, TextInjectionService::class.java).flattenToShortString()
        Settings.Secure.putString(app.contentResolver, services, "org.example/org.example.OtherService:$target")
        assertFalse(TextInjectionService.isEnabled())
        assertTrue(app.isOnboardingAccessibilityConfigured())
    }
}
