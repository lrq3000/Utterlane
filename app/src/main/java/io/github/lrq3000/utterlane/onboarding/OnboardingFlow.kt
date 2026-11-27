package io.github.lrq3000.utterlane.onboarding

import android.os.Build

enum class ModelTier { COMPACT, BALANCED, FULL }

object ModelRecommendation {
    private const val GIB = 1L shl 30
    fun tier(totalRamBytes: Long): ModelTier? = when {
        totalRamBytes <= 0 -> null
        totalRamBytes <= GIB -> ModelTier.COMPACT
        totalRamBytes <= 2 * GIB -> ModelTier.BALANCED
        else -> ModelTier.FULL
    }
}

/** IDs, rather than ordinals, are persisted so pages can be reordered safely. */
enum class OnboardingStep(val id: String, val phase: Int) {
    WELCOME("welcome", 0), USES("uses", 0), MODELS("models", 1),
    DOWNLOAD("download", 1), MICROPHONE("microphone", 1), INPUT("input", 1),
    FOLDERS("folders", 1), SPEAKERS("speakers", 1),
    VOICE_TRIAL("try-voice", 2), FILE_TRIAL("try-file", 2), COMPLETE("finish", 2)
}

/** Navigation is independent of Compose, Android, and the available ASR engines. */
class OnboardingFlow(steps: List<OnboardingStep> = OnboardingStep.entries) {
    private val pages = steps.toList()
    private val indices = pages.withIndex().associate { it.value to it.index }
    private val byId = pages.associateBy { it.id }

    init {
        require(pages.isNotEmpty())
        require(byId.size == pages.size)
    }

    fun restore(id: String): OnboardingStep {
        // An interrupted older wizard can still have the now-merged download page saved.
        val currentId = if (id == "speaker-download") OnboardingStep.SPEAKERS.id else id
        return byId[currentId] ?: pages.first()
    }
    fun next(step: OnboardingStep): OnboardingStep? = move(step, 1)
    fun previous(step: OnboardingStep): OnboardingStep? = move(step, -1)
    private fun move(step: OnboardingStep, offset: Int): OnboardingStep? {
        val index = indices[step] ?: return null
        return pages.getOrNull(index + offset)
    }
}

data class OnboardingProgress(
    val initialized: Boolean = false,
    val completed: Boolean = false,
    val stepId: String = OnboardingStep.WELCOME.id,
    val modelId: String? = null,
    val speakersWanted: Boolean = false,
    val monitorWanted: Boolean = false
) {
    fun shouldLaunch(existingConfiguration: Boolean): Boolean =
        if (initialized) !completed else !existingConfiguration
}

data class OnboardingPermissions(
    val microphone: Boolean = false,
    val audioFiles: Boolean = false,
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val accessibility: Boolean = false,
    val keyboard: Boolean = false,
    val floatingRunning: Boolean = false,
    val accessibilityConfigured: Boolean = false
) {
    fun canRecord(modelReady: Boolean) = modelReady && microphone
    fun canFloat(modelReady: Boolean) = canRecord(modelReady) && overlay && accessibility
    fun canMonitor(modelReady: Boolean, hasFolder: Boolean) = modelReady && audioFiles && hasFolder

    // The installer is a hint, not a permission check: unknown installs also get optional help.
    internal fun shouldShowRestrictedSettingsHelp(sdkInt: Int, installerPackage: String?): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && installerPackage != "com.android.vending" && (!overlay || !accessibilityConfigured)
}
