package io.github.lrq3000.utterlane.asr

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** App-owned capture dependency: production AudioRecord, or a deterministic PCM source in QA. */
class MicrophoneSessionFactory(private val capture: () -> AudioCapture = { AudioRecorder() }) {
    fun create(context: Context, scope: CoroutineScope,
        onText: suspend (String, TranscriptStore) -> Unit,
        onComplete: (TranscriptStore?, SessionFailure?) -> Unit,
        onCaptureEnded: () -> Unit = {}, onWarning: (String) -> Unit = {}, onReady: () -> Unit = {},
        onSessionClosed: () -> Unit = {}, keepResultAudio: Boolean = false,
        openRecoveryOnFailure: Boolean = true): MicrophoneSession =
        MicrophoneSession(context, scope, onText, { store, failure ->
            try { onComplete(store, failure) }
            finally { if (openRecoveryOnFailure) failure?.recoveryId?.let { io.github.lrq3000.utterlane.history.RecordingRecovery.open(context, it) } }
        }, onCaptureEnded, onWarning, onReady, capture(), onSessionClosed, keepResultAudio, openRecoveryOnFailure)
}
