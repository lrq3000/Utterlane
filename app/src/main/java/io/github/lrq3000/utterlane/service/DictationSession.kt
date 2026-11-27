package io.github.lrq3000.utterlane.service

import android.content.Context
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.asr.SessionFailure
import io.github.lrq3000.utterlane.asr.TranscriptStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** All recording dialog hosts share the same capture, Settings decisions, and queue ownership. */
internal class DictationSession(
    context: Context,
    onText: suspend (String, TranscriptStore) -> Unit,
    onComplete: (MicrophoneSession, TranscriptStore?, SessionFailure?) -> Unit,
    onCaptureEnded: () -> Unit = {},
    onWarning: (String) -> Unit = {},
    onReady: () -> Unit = {},
    onClosed: (backgrounded: Boolean) -> Unit = {}
) {
    private val app = UtterlaneApp.instance
    private val context = context.applicationContext
    private val handoff = DictationQueueHandoff.create(context)
    private lateinit var capture: MicrophoneSession
    private var completion: Job? = null
    private var backgroundRequest: (() -> Unit)? = null
    val metrics get() = capture.metrics
    val speakers get() = capture.speakerControl
    val ownsNavigation get() = backgroundRequest != null || handoff.ownsNavigation

    init {
        capture = app.microphoneSessions.create(context, handoff.producerScope, onText,
            onComplete = { store, error ->
                submitBackgroundIfReleased()
                backgroundRequest = null
                completion = handoff.complete(store, error?.message, speakers.retryPreference.value) {
                    onComplete(capture, store, error)
                }
                // Admission failure has no producer and therefore no platform close callback.
                if (error?.kind == SessionFailure.Kind.BUSY) onClosed(false)
            },
            onCaptureEnded = { submitBackgroundIfReleased(); onCaptureEnded() }, onWarning = onWarning, onReady = onReady,
            onSessionClosed = {
                val finalization = completion ?: handoff.interrupted(context.getString(R.string.stream_cancelled))
                // When publication is outstanding, its accepted or foreground callback still owns the host.
                if (finalization != null) app.applicationScope.launch {
                    finalization.join()
                    onClosed(handoff.ownsNavigation)
                } else onClosed(handoff.ownsNavigation)
            },
            keepResultAudio = true, openRecoveryOnFailure = false)
    }

    fun start() = capture.start()
    fun stop() { if (!ownsNavigation) capture.stop() }
    fun cancel(discard: Boolean = true) { if (!ownsNavigation) capture.cancel(discard) }

    fun stopInBackground(accepted: () -> Unit) {
        if (ownsNavigation || completion != null) return
        backgroundRequest = accepted
        if (capture.captureReleased) submitBackgroundIfReleased() else capture.stop()
    }

    private fun submitBackgroundIfReleased() {
        val accepted = backgroundRequest ?: return
        // Before release the microphone foreground service still owns capture. A tap/drop
        // before any samples exist must not leave a spurious failed row in the queue.
        if (!capture.captureReleased || metrics.state.value.capturedSamples == 0L) return
        val audio = capture.audioId ?: return
        backgroundRequest = null
        handoff.submit(audio, speakers.retryPreference.value == true, accepted) { notifyDictationQueueFailure(context, it) }
    }

    fun confirmSettings(phase: CapturePhase, open: () -> Unit): Boolean {
        if (metrics.state.value.phase != phase || ownsNavigation) return false
        return when (phase) {
            CapturePhase.CAPTURING -> capture.discardWhileCapturing().also { if (it) open() }
            CapturePhase.PROCESSING -> capture.audioId?.takeIf { capture.captureReleased }?.let { audio ->
                handoff.submit(audio, speakers.retryPreference.value == true, open) {
                    notifyDictationQueueFailure(context, it)
                }
            } ?: false
            else -> false
        }
    }
}

/** Editors using streaming delivery share cleanup without making another destination eligible for late text. */
internal fun StreamingTextTarget.finishDictation(context: Context, capture: MicrophoneSession,
    store: TranscriptStore?, error: SessionFailure?, afterDelivery: (() -> Unit)?,
    retireAfterPrivateRecovery: Boolean = false, showAudioRecovery: Boolean = true) {
    val audio = capture.audioId
    val retireAudio = { finishForegroundDictation(context, audio, null) }
    finish(store, preserve = error != null, afterDelivery = afterDelivery,
        onResultDelivered = retireAudio, onResultPreserved = retireAudio.takeIf { retireAfterPrivateRecovery })
    if (error != null || store == null) finishForegroundDictation(context, audio, error, openRecovery = showAudioRecovery)
}
