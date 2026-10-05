package io.github.lrq3000.utterlane.diagnostics

import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.asr.CaptureSnapshot
import io.github.lrq3000.utterlane.asr.RecognitionActivity
import io.github.lrq3000.utterlane.asr.RecognitionStatus
import io.github.lrq3000.utterlane.asr.RecognitionStage
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.util.concurrent.atomic.AtomicLong

/** Application-owned sink. Retains one request's throttle, never a map of past sessions. */
class LocalRecognitionDiagnostics internal constructor(
    private val log: BoundedDiagnosticLog,
    private val clock: () -> Long = { System.nanoTime() / 1000000 }
) {
    private var last: RecognitionStatus? = null
    private var lastLogged = Long.MIN_VALUE
    private var operation = 0L
    private val captureIds = AtomicLong()

    @Synchronized fun activity(activity: RecognitionActivity, options: RuntimeOptions) {
        if (!options.diagnostics) return
        val status = RecognitionStatus.from(activity)
        val old = last
        // Failed prepare publishes a request error, then close publishes an ID-0
        // disconnect error. They describe the same failure, not two independent events.
        if (!status.active && status.requestId == 0 && status.stage == RecognitionStage.ERROR &&
            old?.stage == RecognitionStage.ERROR && !old.active) return
        val newRequest = old == null || old.requestId != status.requestId ||
            (status.active && (!old.active || status.elapsedMillis < old.elapsedMillis))
        if (newRequest) { operation++; lastLogged = Long.MIN_VALUE }
        val now = clock()
        // Stage changes alone do not bypass throttling: native callbacks may be frequent.
        // A terminal/error sample is emitted once, even if the 250 ms ticker repeats it.
        val terminal = !status.active && (old == null || old.active || old.stage != status.stage || newRequest)
        if (terminal || (status.active && (lastLogged == Long.MIN_VALUE || now - lastLogged >= 1000))) {
            log.offer(DiagnosticRecord.Activity(options, status, operation))
            lastLogged = now
        }
        last = status
    }

    fun capture(options: RuntimeOptions) = CaptureDiagnosticSession(log, options, captureIds.incrementAndGet(), clock)
    suspend fun snapshot() = log.snapshot()
    suspend fun clear() {
        log.clear()
        synchronized(this) { last = null; lastLogged = Long.MIN_VALUE }
    }
}

/** Owns immutable options, never a live preference flow or a retained PCM/UI snapshot. */
class CaptureDiagnosticSession internal constructor(
    private val log: BoundedDiagnosticLog,
    private val options: RuntimeOptions,
    private val id: Long,
    private val clock: () -> Long
) {
    private var lastPhase: CapturePhase? = null
    private var lastLogged = Long.MIN_VALUE
    private var finished = false
    @Synchronized fun record(snapshot: CaptureSnapshot) {
        if (!options.diagnostics || finished) return
        val now = clock()
        if (lastPhase == snapshot.phase && lastLogged != Long.MIN_VALUE && now - lastLogged < 1000) return
        val captured = snapshot.capturedSamples.coerceAtLeast(0)
        log.offer(DiagnosticRecord.Capture(options, snapshot.phase.name.lowercase(), captured,
            snapshot.processedSamples.coerceIn(0, captured), id))
        lastPhase = snapshot.phase
        lastLogged = now
        finished = snapshot.phase in setOf(CapturePhase.COMPLETE, CapturePhase.FAILED, CapturePhase.CANCELLED)
    }
}
