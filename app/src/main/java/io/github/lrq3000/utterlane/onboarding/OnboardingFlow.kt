package io.github.lrq3000.utterlane.onboarding

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
    FOLDERS("folders", 1), SPEAKERS("speakers", 1), SPEAKER_DOWNLOAD("speaker-download", 1),
    VOICE_TRIAL("try-voice", 2), FILE_TRIAL("try-file", 2), COMPLETE("finish", 2)
}

/** Navigation is independent of Compose, Android, and the available ASR engines. */
class OnboardingFlow(steps: List<OnboardingStep> = OnboardingStep.entries) {
    private class Route(val steps: List<OnboardingStep>) {
        val indices = steps.withIndex().associate { it.value to it.index }
        val byId = steps.associateBy { it.id }
    }
    init {
        require(steps.isNotEmpty())
        require(steps.map { it.id }.toSet().size == steps.size)
    }
    // Build both routes once; next/back/restore stay O(1) during navigation.
    private val withSpeakers = Route(steps.toList())
    private val withoutSpeakers = Route(steps.filter { it != OnboardingStep.SPEAKER_DOWNLOAD })
    private fun route(speakers: Boolean) = if (speakers) withSpeakers else withoutSpeakers

    fun restore(id: String, speakers: Boolean): OnboardingStep {
        val route = route(speakers)
        if (!speakers && id == OnboardingStep.SPEAKER_DOWNLOAD.id && OnboardingStep.SPEAKERS in route.indices) {
            return OnboardingStep.SPEAKERS
        }
        return route.byId[id] ?: route.steps.first()
    }
    fun next(step: OnboardingStep, speakers: Boolean): OnboardingStep? = move(step, speakers, 1)
    fun previous(step: OnboardingStep, speakers: Boolean): OnboardingStep? = move(step, speakers, -1)
    private fun move(step: OnboardingStep, speakers: Boolean, offset: Int): OnboardingStep? {
        val route = route(speakers)
        val index = route.indices[step] ?: return null
        return route.steps.getOrNull(index + offset)
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
    val floatingRunning: Boolean = false
) {
    fun canRecord(modelReady: Boolean) = modelReady && microphone
    fun canFloat(modelReady: Boolean) = canRecord(modelReady) && overlay && accessibility
    fun canMonitor(modelReady: Boolean, hasFolder: Boolean) = modelReady && audioFiles && hasFolder
}
