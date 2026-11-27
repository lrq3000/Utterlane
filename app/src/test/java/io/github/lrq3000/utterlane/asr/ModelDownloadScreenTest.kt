package io.github.lrq3000.utterlane.asr

import android.app.Activity
import android.app.Application
import android.view.WindowManager
import io.github.lrq3000.utterlane.onboarding.OnboardingDeviceState
import io.github.lrq3000.utterlane.onboarding.OnboardingStep
import io.github.lrq3000.utterlane.onboarding.OnboardingTransfer
import io.github.lrq3000.utterlane.onboarding.OnboardingUiState
import io.github.lrq3000.utterlane.onboarding.TransferPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ModelDownloadScreenTest {
    @Test fun settingsKeepsTheWindowAwakeForEitherNetworkTransferAndClearsItAfterwards() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val window = controller.get().window
            fun keepOn() = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            val idle = ModelManager.DownloadState.NotStarted
            window.keepModelDownloadScreenOn(ModelManager.DownloadState.Downloading(10), idle)
            assertTrue(keepOn())
            window.keepModelDownloadScreenOn(ModelManager.DownloadState.Error(ModelManager.ErrorType.NETWORK), idle)
            assertFalse(keepOn())
            window.keepModelDownloadScreenOn(idle, ModelManager.DownloadState.Downloading(1))
            assertTrue(keepOn())
            window.keepModelDownloadScreenOn(idle, ModelManager.DownloadState.Downloading(1), speakersVisible = false)
            assertFalse(keepOn())
            window.keepModelDownloadScreenOn(idle, ModelManager.DownloadState.Copying(1))
            assertFalse(keepOn())
            window.keepModelDownloadScreenOn(idle, ModelManager.DownloadState.Ready)
            assertFalse(keepOn())
        }
    }

    @Test fun onboardingKeepsOnlyTheVisibleNetworkDownloadAwake() {
        val download = OnboardingTransfer(TransferPhase.DOWNLOADING, 23)
        val device = OnboardingDeviceState(speechTransfer = download, speakerTransfer = download)
        val state = OnboardingUiState(step = OnboardingStep.DOWNLOAD, device = device)
        assertTrue(state.keepSetupScreenOn)
        assertTrue(state.copy(step = OnboardingStep.SPEAKERS).keepSetupScreenOn)
        assertFalse(state.copy(step = OnboardingStep.WELCOME).keepSetupScreenOn)
        assertFalse(state.copy(step = OnboardingStep.SPEAKERS,
            device = device.copy(speakerTransfer = OnboardingTransfer())).keepSetupScreenOn)
        assertFalse(state.copy(device = device.copy(speechTransfer = OnboardingTransfer(TransferPhase.IMPORTING))).keepSetupScreenOn)
        assertFalse(state.copy(device = device.copy(speechTransfer = OnboardingTransfer(TransferPhase.ERROR))).keepSetupScreenOn)
    }
}
