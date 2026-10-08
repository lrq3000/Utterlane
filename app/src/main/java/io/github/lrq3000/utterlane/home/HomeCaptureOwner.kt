package io.github.lrq3000.utterlane.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class HomeCapturePhase { IDLE, STARTING, RECORDING, STOPPING, PROCESSING }
internal data class HomeCaptureState(val phase: HomeCapturePhase = HomeCapturePhase.IDLE, val message: String? = null) {
    val active get() = phase != HomeCapturePhase.IDLE
}

/** Main-thread session ownership, independent of Android so callback races can be tested. */
internal class HomeCaptureOwner<R>(
    private val factory: (Events<R>) -> Driver,
    private val onAccepted: () -> Unit,
    private val onResult: (R) -> Unit
) {
    interface Driver { fun start(); fun stop(); fun interrupt() }
    interface Events<R> {
        fun ready()
        fun captureEnded()
        fun result(result: R)
        fun rejected(message: String)
        fun closed()
    }
    private val mutable = MutableStateFlow(HomeCaptureState())
    val state: StateFlow<HomeCaptureState> = mutable
    private var generation = 0L
    private var driver: Driver? = null
    private var accepted = false
    private var ready = false
    private var stopRequested = false
    private var stopSent = false
    private var interrupted = false
    private var completed = false

    fun start(): Boolean {
        if (state.value.active) return false
        val token = ++generation
        accepted = false; ready = false; stopRequested = false; stopSent = false; interrupted = false; completed = false
        mutable.value = HomeCaptureState(HomeCapturePhase.STARTING)
        val events = object : Events<R> {
            private fun current() = token == generation && state.value.active
            private fun accept() {
                if (!accepted) { accepted = true; onAccepted() }
            }
            override fun ready() {
                if (!current() || ready || interrupted || completed || state.value.phase == HomeCapturePhase.PROCESSING) return
                ready = true
                accept()
                mutable.value = state.value.copy(phase = if (stopRequested) HomeCapturePhase.STOPPING else HomeCapturePhase.RECORDING)
                deliverStop()
            }
            override fun captureEnded() {
                if (current()) mutable.value = state.value.copy(phase = HomeCapturePhase.PROCESSING)
            }
            override fun result(result: R) {
                if (!current() || completed) return
                completed = true
                accept()
                mutable.value = state.value.copy(phase = HomeCapturePhase.PROCESSING)
                onResult(result)
            }
            override fun rejected(message: String) {
                if (!current()) return
                // BUSY has no session-closed callback in MicrophoneSession. It
                // never acquired input, so it must not acknowledge the old result.
                mutable.value = HomeCaptureState(message = message)
                driver = null
                generation++
            }
            override fun closed() {
                if (!current()) return
                mutable.value = HomeCaptureState(message = state.value.message)
                driver = null
                generation++
            }
        }
        try {
            driver = factory(events)
            driver!!.start()
            deliverStop()
        } catch (error: Exception) { events.rejected(error.message ?: error.javaClass.simpleName) }
        return true
    }

    fun stop() {
        if (!state.value.active || state.value.phase == HomeCapturePhase.PROCESSING || interrupted) return
        stopRequested = true
        mutable.value = state.value.copy(phase = HomeCapturePhase.STOPPING)
        deliverStop()
    }

    private fun deliverStop() {
        // AudioRecorder resets its stop flag while opening. Waiting for onReady
        // prevents a rapid second tap from being lost before the recorder exists.
        if (ready && stopRequested && !stopSent && driver != null) {
            stopSent = true
            driver!!.stop()
        }
    }

    fun interrupt() {
        if (!state.value.active || interrupted) return
        interrupted = true
        driver?.interrupt()
    }
}
