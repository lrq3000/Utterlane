package io.github.lrq3000.utterlane.transcribe

import io.github.lrq3000.utterlane.asr.CaptureSnapshot

enum class FileProgressStage { PREPARING, TRANSCRIBING, FINALIZING, SPEAKERS, SAVING, COMPLETE, FAILED, CANCELLED }

data class FileProgressSnapshot(
    val stage: FileProgressStage = FileProgressStage.PREPARING,
    val processedSamples: Long = 0,
    val totalSamples: Long? = null,
    val estimatedTotal: Boolean = false,
    val percent: Int? = null,
    val remainingSeconds: Double? = null,
    val additionalFinishing: Boolean = false
)

/** Constant-size accounting for a file, independent of the live microphone tail.
 * Input duration is a denominator, never evidence that recognition has finished.
 * IO events and the UI presentation clock share this owner without polling files. */
class FileTranscriptionProgress(totalSamples: Long? = null, estimatedTotal: Boolean = false) {
    private var total = totalSamples?.takeIf { it >= 0 }
    private var approximate = estimatedTotal && total != null
    private var inputFinished = false
    private var stage = FileProgressStage.PREPARING
    private var speakerFinisher = false

    @Synchronized fun start(hasSpeakerFinalization: Boolean) {
        if (stage != FileProgressStage.PREPARING) return
        speakerFinisher = hasSpeakerFinalization
        stage = FileProgressStage.TRANSCRIBING
    }

    @Synchronized fun inputEnded(samples: Long) {
        require(samples >= 0)
        if (terminal()) return
        total = samples
        approximate = false
        inputFinished = true
    }

    @Synchronized fun finalizing(speakers: Boolean) {
        if (!terminal()) stage = if (speakers) FileProgressStage.SPEAKERS else FileProgressStage.FINALIZING
    }
    @Synchronized fun saving() { if (!terminal()) stage = FileProgressStage.SAVING }
    @Synchronized fun complete() { if (!terminal()) stage = FileProgressStage.COMPLETE }
    @Synchronized fun fail(cancelled: Boolean = false) {
        if (!terminal()) stage = if (cancelled) FileProgressStage.CANCELLED else FileProgressStage.FAILED
    }

    @Synchronized fun snapshot(capture: CaptureSnapshot): FileProgressSnapshot {
        val processed = capture.processedSamples.coerceIn(0, capture.capturedSamples.coerceAtLeast(0))
        // Container metadata can underestimate VBR/decoded duration. Once
        // contradicted, discard it until EOF rather than pinning the bar at 99%.
        if (!inputFinished && total?.let { capture.capturedSamples > it } == true) {
            total = null
            approximate = false
        }
        val knownTotal = total
        val exhausted = knownTotal != null && processed >= knownTotal
        val visibleStage = if (stage == FileProgressStage.TRANSCRIBING && inputFinished && exhausted)
            FileProgressStage.FINALIZING else stage
        val measurable = visibleStage == FileProgressStage.TRANSCRIBING && knownTotal != null && knownTotal > processed
        val percent = when {
            stage == FileProgressStage.COMPLETE -> 100
            measurable -> (processed.toDouble() / checkNotNull(knownTotal) * 100).toInt().coerceIn(0, 99)
            else -> null
        }
        // Reuse the real window EMA. Ticks do not count down, invent work, or
        // extrapolate an unmeasured speaker EOF drain from unrelated throughput.
        val remaining = if (measurable) capture.processingSecondsPerSample
            ?.takeIf { it.isFinite() && it > 0 }
            ?.times(checkNotNull(knownTotal) - processed)
            ?.takeIf { it.isFinite() && it > 0 } else null
        return FileProgressSnapshot(visibleStage, processed, knownTotal, approximate, percent, remaining,
            additionalFinishing = speakerFinisher && visibleStage == FileProgressStage.TRANSCRIBING)
    }

    private fun terminal() = stage == FileProgressStage.COMPLETE || stage == FileProgressStage.FAILED || stage == FileProgressStage.CANCELLED
}
