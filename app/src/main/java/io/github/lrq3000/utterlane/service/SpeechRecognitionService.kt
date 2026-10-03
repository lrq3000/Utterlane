package io.github.lrq3000.utterlane.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.UtterlaneApp
import kotlinx.coroutines.*

/** Standard offline SpeechRecognizer integration with requested cumulative partial results. */
class SpeechRecognitionService : RecognitionService() {
    companion object { private const val TAG = "SpeechRecognitionService" }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var microphoneSession: MicrophoneSession? = null

    override fun onCreate() { super.onCreate(); Log.i(TAG, "SpeechRecognitionService created") }
    override fun onDestroy() { super.onDestroy(); Log.i(TAG, "SpeechRecognitionService destroyed"); cleanup(); serviceScope.cancel() }
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.i(TAG, "onStartListening called; language hint=${recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)}")
        if (listener == null) return
        if (microphoneSession != null) { deliver { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }; return }
        val partial = recognizerIntent?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) == true
        microphoneSession = UtterlaneApp.instance.microphoneSessions.create(this, serviceScope,
            onText = { _, store ->
                if (partial) {
                    val text = withContext(Dispatchers.IO) { store.readForTransfer() }
                    if (text != null && !deliver { listener.partialResults(results(text)) }) {
                        microphoneSession?.stop()
                        // Stop and drain first. Publishing a live recovery file
                        // would let its viewer delete audio-session output in flight.
                    }
                }
            }, onComplete = { store, error ->
                microphoneSession = null
                TranscriptFinalization.deliver(this, store) {
                    val text = withContext(Dispatchers.IO) { store?.readForTransfer() }
                    if (error != null) { deliver { listener.error(error.recognitionError()) }; false }
                    else if (text == null) { deliver { listener.error(SpeechRecognizer.ERROR_CLIENT) }; false }
                    else if (text.isBlank()) { deliver { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }; false }
                    else deliver { listener.results(results(text)) }
                }
            }, onCaptureEnded = { deliver { listener.endOfSpeech() } },
            onWarning = { Log.w(TAG, it) },
            onReady = { if (!deliver { listener.readyForSpeech(Bundle()); listener.beginningOfSpeech() }) microphoneSession?.stop() })
        microphoneSession?.start()
    }
    override fun onStopListening(listener: Callback?) { Log.i(TAG, "onStopListening called"); microphoneSession?.stop() }
    override fun onCancel(listener: Callback?) { Log.i(TAG, "onCancel called"); cleanup() }
    private fun results(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }
    private fun cleanup() { microphoneSession?.cancel(); microphoneSession = null }
    private fun deliver(action: () -> Unit): Boolean = try { action(); true }
        catch (e: android.os.RemoteException) { Log.w(TAG, "Voice-input client disconnected", e); false }
}
