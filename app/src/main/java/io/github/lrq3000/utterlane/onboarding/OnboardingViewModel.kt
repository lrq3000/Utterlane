package io.github.lrq3000.utterlane.onboarding

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OnboardingUiState(
    val ready: Boolean = false,
    val progress: OnboardingProgress = OnboardingProgress(),
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val device: OnboardingDeviceState = OnboardingDeviceState(),
    val choice: OnboardingModel? = null,
    val recommendedId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val trial: VoiceTrialState = VoiceTrialState(),
    val finished: Boolean = false,
    val exit: Boolean = false
) {
    // Fixed-one labeling does not require the auxiliary model, even during a replay.
    val speakerModelNeeded: Boolean get() = progress.speakersWanted && device.preferences.speakerCount != 1 &&
        device.speakerTransfer.phase != TransferPhase.READY
    val modelReady: Boolean get() = choice?.id == device.selectedModelId && device.speechTransfer.phase == TransferPhase.READY
    val floatingActive: Boolean get() = device.preferences.floating && device.permissions.floatingRunning && device.permissions.canFloat(modelReady)
    val transfer: OnboardingTransfer get() = if (step == OnboardingStep.SPEAKERS) device.speakerTransfer else device.speechTransfer
    internal val keepSetupScreenOn get() = trial.active ||
        (step == OnboardingStep.DOWNLOAD || step == OnboardingStep.SPEAKERS) && transfer.phase == TransferPhase.DOWNLOADING
}

class OnboardingViewModel(
    private val services: OnboardingServices,
    private val repository: OnboardingRepository,
    private val message: (Int) -> String
) : ViewModel() {
    private data class Interaction(val ready: Boolean = false, val busy: Boolean = false,
        val error: String? = null, val finished: Boolean = false, val exit: Boolean = false)
    private val interaction = MutableStateFlow(Interaction())
    private val device = services.observe(viewModelScope)
    private val trial = OnboardingVoiceTrial(viewModelScope) { services.voiceSession(viewModelScope, it) }
    private var operation: Job? = null
    private var transferTarget: Boolean? = null
    private var started = false
    private var replayRequested = false
    private val returnedResults = Mutex()
    private var indexedModels: List<OnboardingModel> = emptyList()
    private var modelsById = emptyMap<String, OnboardingModel>()

    val state = combine(repository.progress, device, interaction, trial.state) { progress, current, work, voice ->
        if (indexedModels != current.models) {
            indexedModels = current.models
            modelsById = current.models.associateBy { it.id }
        }
        val tier = ModelRecommendation.tier(current.ramBytes)
        OnboardingUiState(work.ready, progress, OnboardingContent.flow.restore(progress.stepId), current,
            modelsById[progress.modelId] ?: modelsById[current.selectedModelId],
            tier?.let { wanted -> current.models.firstOrNull { it.tier == wanted }?.id },
            work.busy, work.error, voice, work.finished, work.exit)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState())

    fun initialize(replay: Boolean) {
        if (started) return
        started = true
        replayRequested = replay
        runOperation {
            services.initialize()
            val current = device.first { it.initialized }
            val saved = repository.progress.first()
            val tier = ModelRecommendation.tier(current.ramBytes)
            // The launcher has already classified this installation. A theme
            // choice made while this fresh wizard loads must not reclassify it
            // as an existing user and replace the low-memory recommendation.
            val default = if (replay || saved.completed)
                current.selectedModelId else current.models.firstOrNull { it.tier == tier && tier != null }?.id ?: current.selectedModelId
            repository.begin(replay, default, current.preferences.speakers, current.preferences.monitor)
            interaction.value = interaction.value.copy(ready = true)
        }
    }

    fun refreshPermissions() = services.refreshPermissions()
    fun showError(error: String) { interaction.value = interaction.value.copy(error = error) }

    fun act(action: OnboardingAction) {
        when (action) {
            OnboardingAction.RetryInitialization -> initialize(replayRequested)
            is OnboardingAction.Appearance -> viewModelScope.launch {
                try { services.setTheme(action.mode) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { showError(error.message ?: message(R.string.model_error_storage)) }
            }
            OnboardingAction.Back -> back()
            OnboardingAction.CancelDownload -> when (state.value.step) {
                OnboardingStep.DOWNLOAD -> cancelAndMove(OnboardingStep.MODELS)
                OnboardingStep.SPEAKERS -> {
                    if (transferTarget == true) cancelAndMove()
                    else services.cancelTransfer(true)
                }
                else -> Unit
            }
            OnboardingAction.StopRecording -> trial.stop()
            OnboardingAction.CancelRecording -> trial.cancel()
            is OnboardingAction.EditText -> trial.setText(action.text)
            // Enter cancellable preparation before any suspension; the session loads the model without waiting to start capture.
            OnboardingAction.Record -> trial.start()
            is OnboardingAction.SelectModel -> runOperation { repository.update { it.copy(modelId = action.id) } }
            is OnboardingAction.Monitor -> runOperation { repository.update { it.copy(monitorWanted = action.enabled) } }
            is OnboardingAction.RemoveFolder -> runOperation { services.removeFolder(action.path) }
            is OnboardingAction.Speakers -> runOperation { repository.update { it.copy(speakersWanted = action.enabled) } }
            OnboardingAction.EnableFloating -> runOperation { services.enableFloating() }
            is OnboardingAction.Download -> startTransfer(action.speakers)
            OnboardingAction.Skip -> skip()
            OnboardingAction.Next -> next()
            else -> Unit // Android handoffs are owned by the Activity, not the controller.
        }
    }

    private fun next() {
        if (trial.state.value.active) return
        val current = state.value
        when (current.step) {
            OnboardingStep.MODELS -> startTransfer(false)
            OnboardingStep.DOWNLOAD -> runOperation {
                check(services.verify(false)) { message(R.string.onboarding_download_needed) }
                advance()
            }
            OnboardingStep.FOLDERS -> runOperation { services.setMonitor(current.progress.monitorWanted); advance() }
            OnboardingStep.SPEAKERS -> {
                if (!current.speakerModelNeeded && !current.transfer.active) runOperation {
                    services.setSpeakers(current.progress.speakersWanted)
                    advance()
                }
            }
            OnboardingStep.COMPLETE -> runOperation {
                repository.complete()
                interaction.value = interaction.value.copy(finished = true)
            }
            else -> runOperation { advance() }
        }
    }

    private suspend fun advance() {
        val step = OnboardingContent.flow.restore(repository.progress.first().stepId)
        OnboardingContent.flow.next(step)?.let { move(it) }
    }
    private suspend fun move(step: OnboardingStep) = repository.update { it.copy(stepId = step.id) }

    private fun back() {
        if (trial.ownsNavigation) return
        trial.cancel()
        val previous = OnboardingContent.flow.previous(state.value.step)
        if (previous == null) interaction.value = interaction.value.copy(exit = true)
        else if (transferTarget != null) cancelAndMove(previous)
        else runOperation { move(previous) }
    }

    private fun skip() {
        val current = state.value
        trial.cancel()
        runOperation {
            // A replay is an introduction, not a reset. Skipping leaves existing feature settings intact; only an explicit Continue applies a toggle.
            when (current.step) {
                OnboardingStep.FOLDERS -> repository.update { it.copy(monitorWanted = device.value.preferences.monitor) }
                OnboardingStep.SPEAKERS -> repository.update { it.copy(speakersWanted = device.value.preferences.speakers) }
                else -> Unit
            }
            advance()
        }
    }

    fun startTransfer(speakers: Boolean, folder: Uri? = null) {
        if (folder != null) acceptReturnedResult { performTransfer(speakers, folder) }
        else runOperation { performTransfer(speakers, null) }
    }

    private suspend fun performTransfer(speakers: Boolean, folder: Uri?) {
        if (!speakers) {
            services.selectModel(checkNotNull(state.value.choice).id)
            move(OnboardingStep.DOWNLOAD)
        }
        // Installing the optional model does not commit or alter the separate default-label preference.
        transferTarget = speakers
        if (folder != null || !services.verify(speakers)) services.transfer(speakers, folder)
        check(services.verify(speakers)) { message(R.string.model_error_checksum) }
    }

    fun selectedFolder(path: String) = acceptReturnedResult { services.addFolder(path) }

    private fun acceptReturnedResult(block: suspend () -> Unit) {
        viewModelScope.launch {
            // ActivityResult may be delivered while a recreated controller is
            // still loading. These are completed user choices, not repeat taps:
            // retain and serialize them instead of using the drop-if-busy guard.
            returnedResults.withLock {
                state.first { it.ready }
                while (operation?.isActive == true) operation?.join()
                runOperation(block)
                operation?.join()
            }
        }
    }

    private fun cancelAndMove(step: OnboardingStep? = null) {
        val previous = operation
        transferTarget?.let { services.cancelTransfer(it) }
        previous?.cancel()
        viewModelScope.launch {
            previous?.join()
            interaction.value = interaction.value.copy(error = null)
            // The inline speaker Cancel has no destination and must not undo a concurrent Back action.
            step?.let { move(it) }
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        interaction.value = interaction.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!interaction.value.ready) started = false
                Log.w("Onboarding", "Setup operation failed", error)
                showError(error.message ?: message(R.string.model_error_storage))
            } finally { transferTarget = null; interaction.value = interaction.value.copy(busy = false) }
        }
    }

    fun stopForBackground() = trial.stopForBackground()
    internal fun confirmDictationSettings(phase: io.github.lrq3000.utterlane.asr.CapturePhase, open: () -> Unit) =
        trial.confirmSettings(phase, open)
    override fun onCleared() {
        trial.cancel()
        // The scope cancels the request when the wizard is destroyed; explicit navigation away during a transfer or cancelling it discards the checkpoint.
        super.onCleared()
    }

    class Factory(private val app: UtterlaneApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OnboardingViewModel(AppOnboardingServices(app), OnboardingRepository(app), app::getString) as T
    }
}
