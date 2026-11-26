package io.github.lrq3000.utterlane.onboarding

import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.asr.CaptureSnapshot
import io.github.lrq3000.utterlane.asr.LiveSpeakerControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TrialPhase { IDLE, PREPARING, RECORDING, PROCESSING }

/** The keyboard panel observes this session directly, without routing waveform frames through onboarding state. */
class VoiceTrialPanel(val metrics: StateFlow<CaptureSnapshot>, val speakers: LiveSpeakerControl)

data class VoiceTrialState(
    val text: String = "", val phase: TrialPhase = TrialPhase.IDLE,
    val error: String? = null, val warning: String? = null,
    val panel: VoiceTrialPanel? = null, val preview: String = "", val releasing: Boolean = false
) { val active: Boolean get() = phase != TrialPhase.IDLE }

interface OnboardingTrialSession {
    val panel: VoiceTrialPanel
    val ownsNavigation: Boolean get() = false
    fun start()
    fun stop()
    fun cancel(discard: Boolean)
    fun confirmSettings(phase: CapturePhase, accepted: () -> Unit): Boolean
}

/** Bounded, lifecycle-aware trial; its capture implementation is supplied by the app. */
class OnboardingVoiceTrial(
    private val scope: CoroutineScope,
    private val factory: (Callbacks) -> OnboardingTrialSession
) {
    companion object { const val MAX_TEXT = 8000; private const val MAX_DURATION_MS = 120_000L }
    data class Callbacks(
        val onReady: () -> Unit,
        val onText: (String) -> Unit,
        val onCaptureEnded: () -> Unit,
        val onComplete: (String?) -> Unit,
        val onWarning: (String) -> Unit,
        val onClosed: () -> Unit
    )
    private val _state = MutableStateFlow(VoiceTrialState())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var releasingGeneration: Long? = null
    private var session: OnboardingTrialSession? = null
    private var timeout: Job? = null
    val ownsNavigation get() = session?.ownsNavigation == true

    private fun bounded(text: String) = text.takeLast(MAX_TEXT).let {
        if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it
    }

    fun setText(text: String) {
        if (!state.value.active) _state.value = state.value.copy(text = bounded(text))
    }

    fun start() {
        if (session != null || state.value.active || state.value.releasing) return
        val token = ++generation
        val prefix = state.value.text.trim()
        _state.value = state.value.copy(phase = TrialPhase.PREPARING, error = null, warning = null)
        val callbacks = Callbacks(
            onReady = {
                if (token == generation && state.value.phase == TrialPhase.PREPARING) {
                    _state.value = state.value.copy(phase = TrialPhase.RECORDING)
                    timeout = scope.launch { delay(MAX_DURATION_MS); stop() }
                }
            },
            onText = { text ->
                if (token == generation && state.value.active) {
                    _state.value = state.value.copy(preview = bounded(text),
                        text = bounded(listOf(prefix, text).filter { it.isNotBlank() }.joinToString(" ")))
                }
            },
            onCaptureEnded = {
                if (token == generation && state.value.active) {
                    timeout?.cancel()
                    _state.value = state.value.copy(phase = TrialPhase.PROCESSING)
                }
            },
            onComplete = { error ->
                if (token == generation) {
                    close(error)
                }
            },
            onWarning = { if (token == generation) _state.value = state.value.copy(warning = it) },
            onClosed = {
                when (token) {
                    releasingGeneration -> {
                        releasingGeneration = null
                        _state.value = state.value.copy(releasing = false)
                    }
                    generation -> close()
                }
            }
        )
        try {
            val current = factory(callbacks)
            session = current
            _state.value = state.value.copy(panel = current.panel)
            current.start()
        } catch (error: Exception) {
            generation++
            val previous = session
            close(error.message)
            previous?.cancel(discard = true)
        }
    }

    fun stop() {
        val current = state.value
        if (!current.active || current.phase == TrialPhase.PROCESSING) return
        // Native capture publishes before its Main callbacks; only an actually open microphone needs a Stop.
        val nativePhase = current.panel?.metrics?.value?.phase
        when (nativePhase) {
            CapturePhase.CAPTURING, CapturePhase.STOPPING, CapturePhase.PROCESSING -> {
                timeout?.cancel()
                _state.value = current.copy(phase = TrialPhase.PROCESSING)
                if (nativePhase == CapturePhase.CAPTURING) session?.stop()
            }
            else -> Unit
        }
    }

    fun stopForBackground() {
        val current = state.value
        val nativePhase = current.panel?.metrics?.value?.phase
        // Cancel a not-yet-started setup, but preserve any PCM that races with backgrounding.
        if (current.phase == TrialPhase.PREPARING && (nativePhase == null || nativePhase == CapturePhase.LOADING)) {
            cancel(discard = false)
        } else stop()
    }

    fun confirmSettings(phase: CapturePhase, openSettings: () -> Unit) {
        val current = session ?: return
        val token = generation
        current.confirmSettings(phase) accepted@{
            if (session !== current || generation != token) return@accepted
            // The shared session already owns discard or queueing. Detach just this practice field and let capture close normally.
            val previous = generation++
            // Processing handoffs already released the microphone; only a live discard needs a release fence.
            releasingGeneration = previous.takeIf { phase == CapturePhase.CAPTURING }
            close()
            openSettings()
        }
    }

    fun cancel(discard: Boolean = true) {
        if (ownsNavigation) return
        // Invalidate callbacks before asking capture to stop: teardown can invoke
        // callbacks synchronously, or deliver them after a subsequent trial starts. Only onClosed releases admission.
        val previousGeneration = generation++
        val previous = session
        if (previous != null) releasingGeneration = previousGeneration
        close()
        previous?.cancel(discard)
    }

    private fun close(error: String? = state.value.error) {
        timeout?.cancel(); timeout = null; session = null
        _state.value = state.value.copy(phase = TrialPhase.IDLE, panel = null, preview = "",
            error = error, releasing = releasingGeneration != null)
    }
}
