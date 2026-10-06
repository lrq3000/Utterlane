package io.github.lrq3000.utterlane.onboarding

import org.junit.Assert.*
import org.junit.Test

class OnboardingFlowTest {
    private val gib = 1L shl 30

    @Test fun recommendationsIncludeTheOneAndTwoGiBBoundaries() {
        assertEquals(ModelTier.COMPACT, ModelRecommendation.tier(gib - 1))
        assertEquals(ModelTier.COMPACT, ModelRecommendation.tier(gib))
        assertEquals(ModelTier.BALANCED, ModelRecommendation.tier(gib + 1))
        assertEquals(ModelTier.BALANCED, ModelRecommendation.tier(2 * gib))
        assertEquals(ModelTier.FULL, ModelRecommendation.tier(2 * gib + 1))
        assertEquals(ModelTier.FULL, ModelRecommendation.tier(Long.MAX_VALUE))
    }

    @Test fun unknownMemoryDoesNotPretendToKnowTheBestModel() {
        assertNull(ModelRecommendation.tier(0))
        assertNull(ModelRecommendation.tier(-1))
    }

    @Test fun optionalDownloadIsPresentOnlyForTheChosenFeature() {
        val flow = OnboardingFlow()
        assertEquals(OnboardingStep.VOICE_TRIAL, flow.next(OnboardingStep.SPEAKERS, false))
        assertEquals(OnboardingStep.SPEAKER_DOWNLOAD, flow.next(OnboardingStep.SPEAKERS, true))
        assertEquals(OnboardingStep.SPEAKERS, flow.previous(OnboardingStep.VOICE_TRIAL, false))
        assertEquals(OnboardingStep.SPEAKER_DOWNLOAD, flow.previous(OnboardingStep.VOICE_TRIAL, true))
        assertNull(flow.previous(OnboardingStep.WELCOME, false))
        assertNull(flow.next(OnboardingStep.COMPLETE, true))
    }

    @Test fun persistedIdsSurviveReorderingAndRemovedScreensFallBackSafely() {
        val flow = OnboardingFlow(listOf(OnboardingStep.WELCOME, OnboardingStep.MODELS, OnboardingStep.COMPLETE))
        assertEquals(OnboardingStep.MODELS, flow.restore("models", false))
        assertEquals(OnboardingStep.WELCOME, flow.restore("input", false))
        assertEquals(OnboardingStep.WELCOME, flow.restore("unknown-future-page", true))
        assertEquals(OnboardingStep.COMPLETE, flow.next(OnboardingStep.MODELS, false))
    }

    @Test fun disablingSpeakerChoiceWhileResumingSkipsItsDownload() {
        assertEquals(OnboardingStep.SPEAKERS, OnboardingFlow().restore("speaker-download", false))
    }

    @Test fun launchPolicyDistinguishesFreshExistingAndInterruptedSetups() {
        assertTrue(OnboardingProgress().shouldLaunch(existingConfiguration = false))
        assertFalse(OnboardingProgress().shouldLaunch(existingConfiguration = true))
        // Theme/model settings written during setup must not make the next launch
        // look like an existing installation and silently skip unfinished steps.
        assertTrue(OnboardingProgress(initialized = true).shouldLaunch(existingConfiguration = true))
        assertFalse(OnboardingProgress(initialized = true, completed = true).shouldLaunch(false))
    }

    @Test fun trialRequirementsDoNotRequireAKeyboardOrLibraryAccess() {
        val permissions = OnboardingPermissions(microphone = true)
        assertTrue(permissions.canRecord(modelReady = true))
        assertFalse(permissions.canRecord(modelReady = false))
        assertFalse(OnboardingPermissions().canRecord(modelReady = true))
        assertFalse(permissions.canFloat(modelReady = true))
        assertTrue(permissions.copy(overlay = true, accessibility = true).canFloat(true))
        assertFalse(permissions.canMonitor(modelReady = true, hasFolder = true))
        assertTrue(permissions.copy(audioFiles = true).canMonitor(true, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateStepIdsAreRejected() {
        OnboardingFlow(listOf(OnboardingStep.WELCOME, OnboardingStep.WELCOME))
    }

    @Test fun floatingStatusRequiresBothRunningServiceAndCurrentPrerequisites() {
        val model = OnboardingModel("speech", "Speech", 1, ModelExplanation.CURRENT)
        val permissions = OnboardingPermissions(microphone = true, overlay = true, accessibility = true)
        val device = OnboardingDeviceState(models = listOf(model), selectedModelId = model.id,
            preferences = OnboardingPreferences(floating = true), permissions = permissions,
            speechTransfer = OnboardingTransfer(TransferPhase.READY, 100))
        val state = OnboardingUiState(choice = model, device = device)
        assertFalse(state.floatingActive)
        val running = device.copy(permissions = permissions.copy(floatingRunning = true))
        assertTrue(state.copy(device = running).floatingActive)
        assertFalse(state.copy(device = running.copy(permissions = running.permissions.copy(microphone = false))).floatingActive)
    }
}
