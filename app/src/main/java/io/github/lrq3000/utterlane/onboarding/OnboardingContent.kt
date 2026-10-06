package io.github.lrq3000.utterlane.onboarding

import androidx.annotation.StringRes
import io.github.lrq3000.utterlane.R

data class OnboardingPage(
    val step: OnboardingStep, @StringRes val kicker: Int,
    @StringRes val heading: Int, @StringRes val body: Int
)

/** Edit this registry to reorder/remove pages; navigation never uses page numbers. */
object OnboardingContent {
    val pages = listOf(
        OnboardingPage(OnboardingStep.WELCOME, R.string.onboarding_welcome_kicker, R.string.onboarding_welcome_heading, R.string.onboarding_welcome_body),
        OnboardingPage(OnboardingStep.USES, R.string.onboarding_uses_kicker, R.string.onboarding_uses_heading, R.string.onboarding_uses_body),
        OnboardingPage(OnboardingStep.MODELS, R.string.onboarding_models_kicker, R.string.onboarding_models_heading, R.string.onboarding_models_body),
        OnboardingPage(OnboardingStep.DOWNLOAD, R.string.onboarding_download_kicker, R.string.onboarding_download_heading, R.string.onboarding_download_body),
        OnboardingPage(OnboardingStep.MICROPHONE, R.string.onboarding_mic_kicker, R.string.onboarding_mic_heading, R.string.onboarding_mic_body),
        OnboardingPage(OnboardingStep.INPUT, R.string.onboarding_input_kicker, R.string.onboarding_input_heading, R.string.onboarding_input_body),
        OnboardingPage(OnboardingStep.FOLDERS, R.string.onboarding_folders_kicker, R.string.onboarding_folders_heading, R.string.onboarding_folders_body),
        OnboardingPage(OnboardingStep.SPEAKERS, R.string.onboarding_speakers_kicker, R.string.onboarding_speakers_heading, R.string.onboarding_speakers_body),
        OnboardingPage(OnboardingStep.SPEAKER_DOWNLOAD, R.string.onboarding_speakers_kicker, R.string.onboarding_download_heading, R.string.onboarding_download_body),
        OnboardingPage(OnboardingStep.VOICE_TRIAL, R.string.onboarding_voice_kicker, R.string.onboarding_voice_heading, R.string.onboarding_voice_body),
        OnboardingPage(OnboardingStep.FILE_TRIAL, R.string.onboarding_voice_kicker, R.string.onboarding_files_heading, R.string.onboarding_files_body),
        OnboardingPage(OnboardingStep.COMPLETE, R.string.onboarding_complete_kicker, R.string.onboarding_complete_heading, R.string.onboarding_complete_body)
    )
    val byStep = pages.associateBy { it.step }
    val flow = OnboardingFlow(pages.map { it.step })
    const val TYPING_STUDY = "https://userinterfaces.aalto.fi/typing37k/"
    const val SPEECH_STUDY = "https://www.isca-archive.org/interspeech_2006/yuan06_interspeech.html"
    const val SAMPLE_SOURCE = "https://commons.wikimedia.org/wiki/File:Alice%27s_Adventures_in_Wonderland,_chapter_1.ogg"
}

enum class SetupPermission { MICROPHONE, AUDIO_FILES, NOTIFICATIONS }
enum class AndroidSetup { APP, KEYBOARD, OVERLAY, ACCESSIBILITY }

sealed interface OnboardingAction {
    data object RetryInitialization : OnboardingAction
    data object Next : OnboardingAction
    data object Back : OnboardingAction
    data object Skip : OnboardingAction
    data object Download : OnboardingAction
    data object CancelDownload : OnboardingAction
    data class Import(val speakers: Boolean) : OnboardingAction
    data class SelectModel(val id: String) : OnboardingAction
    data class Appearance(val mode: String) : OnboardingAction
    data class Monitor(val enabled: Boolean) : OnboardingAction
    data class Speakers(val enabled: Boolean) : OnboardingAction
    data class Permission(val permission: SetupPermission) : OnboardingAction
    data class Settings(val target: AndroidSetup) : OnboardingAction
    data class OpenLink(val url: String) : OnboardingAction
    data object ChooseFolder : OnboardingAction
    data object ChooseDownloads : OnboardingAction
    data class RemoveFolder(val path: String) : OnboardingAction
    data object EnableFloating : OnboardingAction
    data object Record : OnboardingAction
    data object CancelRecording : OnboardingAction
    data class EditText(val text: String) : OnboardingAction
    data object ShareSample : OnboardingAction
}
