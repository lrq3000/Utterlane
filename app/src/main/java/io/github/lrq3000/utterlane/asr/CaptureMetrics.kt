package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.log10
import kotlin.math.sqrt

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
    val recognition: RecognitionStatus = RecognitionStatus.from(RecognitionActivity()),
    val modelLoading: Boolean = false,
    val recognitionError: String? = null
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
    private val mutable = MutableStateFlow(CaptureSnapshot())
    val state: StateFlow<CaptureSnapshot> = mutable
    private val history = FloatArray(64)
    private var cursor = 0
    private var lastFrames = 0L
    private var lastAudible = 0L
    private var blocked = false
    private var processingRate: Double? = null
    private var processedEndSample = 0L

    @Synchronized fun model(name: String) { mutable.value = mutable.value.copy(modelName = name) }
    @Synchronized fun preparingModel() { mutable.value = mutable.value.copy(modelLoading = true, recognitionError = null) }
    @Synchronized fun modelPrepared() { mutable.value = mutable.value.copy(modelLoading = false) }
    @Synchronized fun recognitionFailed(message: String) {
        // A consumer failure is independent of the microphone's live phase.
        mutable.value = mutable.value.copy(modelLoading = false, recognitionError = message)
    }
    @Synchronized fun recognition(activity: RecognitionActivity) {
        if (mutable.value.phase in setOf(CapturePhase.COMPLETE, CapturePhase.FAILED, CapturePhase.CANCELLED)) return
        mutable.value = mutable.value.copy(recognition = RecognitionStatus.from(activity))
    }
    /** File decoding can count accepted PCM without computing or retaining a waveform. */
    @Synchronized fun captured(samples: Int) {
        require(samples >= 0)
        if (mutable.value.phase == CapturePhase.LOADING) started()
        mutable.value = counted(mutable.value, samples)
    }
    private fun counted(snapshot: CaptureSnapshot, samples: Int): CaptureSnapshot {
        val total = snapshot.capturedSamples + samples
        return snapshot.copy(capturedSamples = total, processedSamples = minOf(processedEndSample, total))
    }
    @Synchronized fun started() {
        if (mutable.value.phase != CapturePhase.LOADING) return
        lastFrames = clock(); lastAudible = lastFrames
        mutable.value = mutable.value.copy(phase = CapturePhase.CAPTURING)
    }
    @Synchronized fun samples(pcm: ShortArray, accepted: Boolean) {
        if (mutable.value.phase == CapturePhase.LOADING) started()
        var squares = 0.0
        pcm.forEach { val sample = it / 32768.0; squares += sample * sample }
        val rms = if (pcm.isEmpty()) 0.0 else sqrt(squares / pcm.size)
        val db = 20 * log10(rms.coerceAtLeast(1e-7))
        val now = clock()
        lastFrames = now
        if (db > -55) lastAudible = now
        val level = if (rms == 0.0) 0f else ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
        history[cursor] = level; cursor = (cursor + 1) % history.size
        val visual = FloatArray(history.size) { history[(cursor + it) % history.size] }
        mutable.value = counted(mutable.value.copy(level = level, waveform = visual), if (accepted) pcm.size else 0)
        tick()
    }
    @Synchronized fun tick() {
        if (mutable.value.phase != CapturePhase.CAPTURING && mutable.value.phase != CapturePhase.STOPPING) return
        val now = clock()
        val signal = when {
            blocked -> CaptureSignal.BLOCKED
            now - lastFrames >= 1500 -> CaptureSignal.NO_FRAMES
            now - lastAudible >= 1500 -> CaptureSignal.LOW
            mutable.value.level > 0 -> CaptureSignal.AUDIO
            else -> CaptureSignal.WAITING
        }
        mutable.value = mutable.value.copy(signal = signal, level = if (signal == CaptureSignal.NO_FRAMES || signal == CaptureSignal.BLOCKED) 0f else mutable.value.level)
    }
    @Synchronized fun silenced(value: Boolean) { blocked = value; tick() }
    @Synchronized fun stopping() { mutable.value = mutable.value.copy(phase = CapturePhase.STOPPING) }
    @Synchronized fun captureEnded() { mutable.value = mutable.value.copy(phase = CapturePhase.PROCESSING); progress() }
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
        mutable.value = mutable.value.copy(processedSamples = minOf(end, mutable.value.capturedSamples))
        progress()
    }
    private fun progress() {
        if (mutable.value.phase != CapturePhase.PROCESSING) return
        val snapshot = mutable.value
        val percent = if (snapshot.capturedSamples == 0L) 0 else (snapshot.processedSamples.toDouble() * 100 / snapshot.capturedSamples).toInt().coerceIn(0, 99)
        mutable.value = snapshot.copy(percent = percent,
            remainingSeconds = processingRate?.times((snapshot.capturedSamples - snapshot.processedSamples).coerceAtLeast(0)))
    }
    @Synchronized fun completed(error: String?) {
        mutable.value = mutable.value.copy(phase = if (error == null) CapturePhase.COMPLETE else CapturePhase.FAILED,
            modelLoading = false,
            percent = if (error == null) 100 else mutable.value.percent?.coerceAtMost(99), error = error,
            recognition = mutable.value.recognition.copy(active = false, opaque = false,
                stage = if (error == null) RecognitionStage.FINISHED else RecognitionStage.ERROR))
    }
    @Synchronized fun cancelled() {
        mutable.value = mutable.value.copy(phase = CapturePhase.CANCELLED, modelLoading = false,
            recognition = mutable.value.recognition.copy(active = false, opaque = false))
    }
}
