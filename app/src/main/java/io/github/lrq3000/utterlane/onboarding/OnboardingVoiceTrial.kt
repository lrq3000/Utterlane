package io.github.lrq3000.utterlane.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TrialPhase { IDLE, PREPARING, RECORDING, PROCESSING }
data class VoiceTrialState(
    val text: String = "", val phase: TrialPhase = TrialPhase.IDLE,
    val error: String? = null, val warning: String? = null
) { val active: Boolean get() = phase != TrialPhase.IDLE }

interface OnboardingTrialSession {
    fun start()
    fun stop()
    fun cancel()
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
    private var session: OnboardingTrialSession? = null
    private var timeout: Job? = null

    private fun bounded(text: String) = text.takeLast(MAX_TEXT).let {
        if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it
    }

    fun setText(text: String) {
        if (!state.value.active) _state.value = state.value.copy(text = bounded(text))
    }

    fun start() {
        if (session != null || state.value.active) return
        val token = ++generation
        val prefix = state.value.text.trim()
        _state.value = state.value.copy(phase = TrialPhase.PREPARING, error = null, warning = null)
        val callbacks = Callbacks(
            onReady = {
                if (token == generation && state.value.active) {
                    _state.value = state.value.copy(phase = TrialPhase.RECORDING)
                    timeout = scope.launch { delay(MAX_DURATION_MS); stop() }
                }
            },
            onText = { text ->
                if (token == generation && state.value.active) {
                    _state.value = state.value.copy(text = bounded(listOf(prefix, text).filter { it.isNotBlank() }.joinToString(" ")))
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
                    timeout?.cancel(); session = null
                    _state.value = state.value.copy(phase = TrialPhase.IDLE, error = error)
                }
            },
            onWarning = { if (token == generation) _state.value = state.value.copy(warning = it) },
            onClosed = {
                if (token == generation) {
                    timeout?.cancel(); session = null
                    _state.value = state.value.copy(phase = TrialPhase.IDLE)
                }
            }
        )
        try { session = factory(callbacks); session?.start() }
        catch (error: Exception) {
            session?.cancel(); session = null; timeout?.cancel()
            _state.value = state.value.copy(phase = TrialPhase.IDLE, error = error.message)
        }
    }

    fun stop() {
        when (state.value.phase) {
            TrialPhase.PREPARING -> cancel()
            TrialPhase.RECORDING -> {
                timeout?.cancel()
                _state.value = state.value.copy(phase = TrialPhase.PROCESSING)
                session?.stop()
            }
            else -> Unit
        }
    }

    fun stopForBackground() = stop()

    fun cancel() {
        // Invalidate callbacks before asking capture to stop: teardown can invoke
        // callbacks synchronously, or deliver them after a subsequent trial starts.
        generation++
        timeout?.cancel()
        val previous = session
        session = null
        _state.value = state.value.copy(phase = TrialPhase.IDLE)
        previous?.cancel()
    }
}
