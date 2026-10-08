package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.lrq3000.utterlane.settings.VisualRefreshRate

enum class CapturePhase { LOADING, CAPTURING, STOPPING, PROCESSING, COMPLETE, FAILED, CANCELLED }
enum class CaptureSignal { WAITING, AUDIO, LOW, NO_FRAMES, BLOCKED }
data class CaptureSnapshot(
    val phase: CapturePhase = CapturePhase.LOADING,
    val signal: CaptureSignal = CaptureSignal.WAITING,
    val level: Float = 0f,
    val waveform: FloatArray = FloatArray(64),
    val capturedSamples: Long = 0,
    val processedSamples: Long = 0,
    val percent: Int? = null,
    val remainingSeconds: Double? = null,
    val modelName: String = "",
    val error: String? = null,
    val modelPreparing: Boolean = false,
    val recognitionFailure: String? = null,
    val recognition: RecognitionStatus = RecognitionStatus.from(RecognitionActivity()),
    val input: io.github.lrq3000.utterlane.audio.CaptureInputState = io.github.lrq3000.utterlane.audio.CaptureInputState()
) {
    val capturedSeconds: Double get() = capturedSamples.coerceAtLeast(0) / 16000.0
    val processedSeconds: Double get() = processedSamples.coerceIn(0, capturedSamples.coerceAtLeast(0)) / 16000.0
    // Ownership counts exclude overlapping ASR context and include disk-backed backlog.
    // This is outstanding accepted audio, not a claim about native queue occupancy.
    val backlogSeconds: Double get() = (capturedSeconds - processedSeconds).coerceAtLeast(0.0)
}

/**
 * PCM-derived, in-memory feedback for the recording UI; no persistence or network reporting.
 * The clock is monotonic, the waveform is bounded, and no raw recording is retained here.
 */
class CaptureMetrics(private val clock: () -> Long = { System.nanoTime() / 1000000 }) {
    @Synchronized fun input(value: io.github.lrq3000.utterlane.audio.CaptureInputState) {
        if (terminal()) return
        pending = pending.copy(input = value)
        publish(true)
    }
    private val mutable = MutableStateFlow(CaptureSnapshot())
    val state: StateFlow<CaptureSnapshot> = mutable
    private var pending = mutable.value
    private val waveform = WaveformHistory()
    private var capturedSamples = 0L
    private var fileInput = false
    private var visualHz = VisualRefreshRate.DEFAULT
    private var nextVisual = Long.MIN_VALUE
    private var lastFrames = 0L
    private var lastAudible = 0L
    private var blocked = false
    private var processingRate: Double? = null
    private var processedEndSample = 0L

    @Synchronized fun setVisualRefreshRate(hz: Int) {
        VisualRefreshRate.requireValid(hz)
        if (hz == visualHz) return
        visualHz = hz
        nextVisual = clock() * visualHz + 1000
    }
    @Synchronized fun visualRefreshIntervalMillis(): Long = VisualRefreshRate.intervalMillis(visualHz)
    @Synchronized fun model(name: String) { pending = pending.copy(modelName = name); publish(true) }
    @Synchronized fun preparing(value: Boolean) { pending = pending.copy(modelPreparing = value); publish(true) }
    @Synchronized fun recognitionFailed(message: String) {
        pending = pending.copy(modelPreparing = false, recognitionFailure = message)
        publish(true)
    }
    @Synchronized fun recognition(activity: RecognitionActivity) {
        if (terminal()) return
        pending = pending.copy(recognition = RecognitionStatus.from(activity))
        publish(!activity.active)
    }
    /** File decoding can count accepted PCM without computing or retaining a waveform. */
    @Synchronized fun captured(samples: Int) {
        require(samples >= 0)
        fileInput = true
        if (pending.phase == CapturePhase.LOADING) started()
        capturedSamples += samples
        publish()
    }
    @Synchronized fun started() {
        if (pending.phase != CapturePhase.LOADING) return
        lastFrames = clock(); lastAudible = lastFrames
        pending = pending.copy(phase = CapturePhase.CAPTURING)
        publish(true)
    }
    @Synchronized fun samples(pcm: ShortArray, accepted: Boolean) {
        fileInput = false
        if (pending.phase == CapturePhase.LOADING) started()
        waveform.accept(pcm)
        val now = clock()
        lastFrames = now
        if (waveform.audible) lastAudible = now
        if (accepted) capturedSamples += pcm.size
        tick()
    }
    @Synchronized fun tick() {
        if (terminal()) return
        // File decoding can pause for inference without a microphone failure.
        // Its presentation clock publishes counters, not live signal alarms.
        if (fileInput) { publish(); return }
        if (pending.phase != CapturePhase.CAPTURING && pending.phase != CapturePhase.STOPPING) { publish(); return }
        val now = clock()
        val signal = when {
            blocked -> CaptureSignal.BLOCKED
            now - lastFrames >= 1500 -> CaptureSignal.NO_FRAMES
            now - lastAudible >= 1500 -> CaptureSignal.LOW
            waveform.level > 0 -> CaptureSignal.AUDIO
            else -> CaptureSignal.WAITING
        }
        val changed = signal != pending.signal
        if (changed) pending = pending.copy(signal = signal)
        publish(changed)
    }
    @Synchronized fun silenced(value: Boolean) { blocked = value; tick() }
    @Synchronized fun stopping() { pending = pending.copy(phase = CapturePhase.STOPPING); publish(true) }
    @Synchronized fun captureEnded() {
        if (terminal()) return
        pending = pending.copy(phase = CapturePhase.PROCESSING)
        publish(true)
    }
    @Synchronized fun processed(endSample: Long, milliseconds: Long) {
        // queue.offer can wake a fast consumer before samples() publishes capture counts.
        // Keep the real ownership watermark, but expose only already-counted input. The
        // next capture publication reconciles that race without losing completed work.
        val end = maxOf(endSample, processedEndSample)
        val delta = end - processedEndSample
        if (delta > 0 && milliseconds > 0) {
            val rate = milliseconds / 1000.0 / delta
            processingRate = processingRate?.let { it * 0.8 + rate * 0.2 } ?: rate
        }
        processedEndSample = end
        publish()
    }
    private fun publish(force: Boolean = false) {
        // Scale the millisecond clock by Hz: every deadline is exactly 1000
        // units apart, including fractional periods such as 60/90 Hz. Advance
        // past obsolete slots rather than drifting or replaying delayed frames.
        val now = clock() * visualHz
        if (!force && now < nextVisual) return
        nextVisual = if (force || nextVisual == Long.MIN_VALUE) now + 1000
            else nextVisual + ((now - nextVisual) / 1000 + 1) * 1000
        val processed = processedEndSample.coerceIn(0, capturedSamples)
        pending = pending.copy(capturedSamples = capturedSamples, processedSamples = processed,
            waveform = waveform.snapshot(), level = if (pending.signal in setOf(CaptureSignal.NO_FRAMES, CaptureSignal.BLOCKED)) 0f else waveform.level)
        if (pending.phase == CapturePhase.PROCESSING) {
            pending = pending.copy(percent = if (capturedSamples == 0L) 0 else (processed.toDouble() * 100 / capturedSamples).toInt().coerceIn(0, 99),
                remainingSeconds = processingRate?.times(capturedSamples - processed))
        }
        mutable.value = pending
    }
    @Synchronized fun completed(error: String?) {
        pending = pending.copy(phase = if (error == null) CapturePhase.COMPLETE else CapturePhase.FAILED,
            percent = if (error == null) 100 else pending.percent?.coerceAtMost(99), error = error,
            recognition = pending.recognition.copy(active = false, opaque = false,
                stage = if (error == null) RecognitionStage.FINISHED else RecognitionStage.ERROR))
        publish(true)
    }
    @Synchronized fun cancelled() {
        pending = pending.copy(phase = CapturePhase.CANCELLED,
            recognition = pending.recognition.copy(active = false, opaque = false))
        publish(true)
    }
    private fun terminal() = when (pending.phase) {
        CapturePhase.COMPLETE, CapturePhase.FAILED, CapturePhase.CANCELLED -> true
        else -> false
    }
}
