package com.translander.asr

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** App-owned capture dependency: production AudioRecord, or a deterministic PCM source in QA. */
class MicrophoneSessionFactory(private val capture: () -> AudioCapture = { AudioRecorder() }) {
    fun create(context: Context, scope: CoroutineScope,
        onText: suspend (String, TranscriptStore) -> Unit,
        onComplete: (TranscriptStore?, SessionFailure?) -> Unit,
        onCaptureEnded: () -> Unit = {}, onWarning: (String) -> Unit = {}, onReady: () -> Unit = {}): MicrophoneSession =
        MicrophoneSession(context, scope, onText, onComplete, onCaptureEnded, onWarning, onReady, capture())
}
