package io.github.lrq3000.utterlane.service

import android.content.Context
import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.settings.openSettingsFromDictation
import io.github.lrq3000.utterlane.settings.speakerSettingsAction
import io.github.lrq3000.utterlane.ui.RecordingOverlay
import kotlinx.coroutines.CoroutineScope

/** Floating and accessibility microphones are two triggers for the same editor and overlay adapter. */
internal class StreamingDictationOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
    private val target: StreamingTextTarget,
    private val recordingChanged: (Boolean) -> Unit,
    private val warning: (String) -> Unit,
    private val released: () -> Unit
) {
    private var hosting = true
    private val overlay: RecordingOverlay = RecordingOverlay(context, ::confirmSettings).apply {
        onDoneClick = ::stop
        onCancelClick = { if (!session.ownsNavigation) cancel() }
    }
    private val session: DictationSession = DictationSession(context,
        onText = { delta, store -> target.accept(store); if (hosting) overlay.setStatus(delta) },
        onComplete = { capture, store, error ->
            val hasHost = hosting
            val speakerSettings = if (hasHost) capture.speakerControl.speakerSettingsAction(context) else null
            if (hasHost) {
                detach()
                error?.let { warning(it.message) }
            }
            target.finishDictation(context, capture, store, error, speakerSettings,
                retireAfterPrivateRecovery = true, showAudioRecovery = hasHost)
        },
        onCaptureEnded = { if (hosting) recordingChanged(false) },
        onWarning = { if (hosting) warning(it) },
        onClosed = { backgrounded ->
            if (!backgrounded) target.sessionClosed()
            detach()
        })

    fun start() {
        overlay.show()
        overlay.bind(scope, session.metrics.state, session.speakers)
        recordingChanged(true)
        session.start()
    }

    fun stop() {
        if (!hosting || session.metrics.state.value.phase !in listOf(CapturePhase.LOADING, CapturePhase.CAPTURING)) return
        recordingChanged(false)
        session.stop()
    }

    fun cancel(discard: Boolean = true) {
        if (!hosting) return
        detach()
        target.close()
        session.cancel(discard)
    }

    private fun confirmSettings(phase: CapturePhase) {
        session.confirmSettings(phase) {
            target.close()
            if (hosting) {
                detach()
                openSettingsFromDictation(context)
            }
        }
    }

    private fun detach() {
        if (!hosting) return
        hosting = false
        overlay.hide()
        recordingChanged(false)
        released()
    }
}
