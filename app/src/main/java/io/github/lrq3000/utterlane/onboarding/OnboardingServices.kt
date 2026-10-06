package io.github.lrq3000.utterlane.onboarding

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

enum class ModelExplanation { ULTRA_Q8, ULTRA_Q4, REDUX, ORIGINAL, CURRENT }
data class OnboardingModel(
    val id: String, val name: String, val bytes: Long,
    val explanation: ModelExplanation, val tier: ModelTier? = null
)
enum class TransferPhase { IDLE, DOWNLOADING, IMPORTING, VERIFYING, READY, ERROR }
data class OnboardingTransfer(val phase: TransferPhase = TransferPhase.IDLE, val progress: Int = 0, val error: String? = null) {
    val active: Boolean get() = phase == TransferPhase.DOWNLOADING || phase == TransferPhase.IMPORTING || phase == TransferPhase.VERIFYING
}
data class OnboardingPreferences(
    val theme: String = "system", val floating: Boolean = false, val monitor: Boolean = false,
    val folders: Set<String> = emptySet(), val speakers: Boolean = false, val speakerCount: Int = 0,
    val previouslyConfigured: Boolean = false
)
data class OnboardingDeviceState(
    val initialized: Boolean = false,
    val models: List<OnboardingModel> = emptyList(),
    val selectedModelId: String = "",
    val ramBytes: Long = 0,
    val speakerBytes: Long = 0,
    val preferences: OnboardingPreferences = OnboardingPreferences(),
    val permissions: OnboardingPermissions = OnboardingPermissions(),
    val speechTransfer: OnboardingTransfer = OnboardingTransfer(),
    val speakerTransfer: OnboardingTransfer = OnboardingTransfer()
)

/** The wizard knows capabilities, not the app's recognizer engines or Settings UI. */
interface OnboardingServices {
    fun observe(scope: CoroutineScope): StateFlow<OnboardingDeviceState>
    suspend fun initialize()
    fun refreshPermissions()
    suspend fun selectModel(id: String)
    suspend fun transfer(speakers: Boolean, folder: Uri? = null)
    suspend fun verify(speakers: Boolean): Boolean
    fun cancelTransfer(speakers: Boolean)
    suspend fun setTheme(mode: String)
    suspend fun setSpeakers(enabled: Boolean)
    suspend fun setMonitor(enabled: Boolean)
    suspend fun addFolder(path: String)
    suspend fun removeFolder(path: String)
    suspend fun enableFloating()
    fun voiceSession(scope: CoroutineScope, callbacks: OnboardingVoiceTrial.Callbacks): OnboardingTrialSession
}
