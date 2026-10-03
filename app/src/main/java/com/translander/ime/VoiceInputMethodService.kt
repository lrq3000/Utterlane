package com.translander.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import com.translander.R
import com.translander.TranslanderApp
import com.translander.asr.MicrophoneSession
import com.translander.service.StreamingTextTarget
import com.translander.service.TranscriptRecovery
import com.translander.ui.RecordingUIBuilder
import kotlinx.coroutines.*

/** Auxiliary voice IME. Shared recording UI and bounded microphone pipeline. */
class VoiceInputMethodService : InputMethodService() {
    companion object { private const val TAG = "VoiceInputMethodService" }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var microphoneSession: MicrophoneSession? = null
    private var isRecording = false
    private var editorActive = false
    private var targetEditor: Pair<String?, Int>? = null
    private var statusText: TextView? = null
    private var recordingPanel: com.translander.ui.RecordingPanel? = null

    override fun onCreate() { super.onCreate(); Log.i(TAG, "VoiceInputMethodService created") }
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VoiceInputMethodService destroyed")
        cleanup(); serviceScope.cancel()
    }
    override fun onCreateInputView(): View {
        Log.i(TAG, "onCreateInputView")
        // Same recording bar as VoiceInputActivity.
        val ui = RecordingUIBuilder.createRecordingBar(this,
            onDoneClick = { if (isRecording) stopRecordingAndTranscribe() else if (microphoneSession == null) switchBackToPreviousKeyboard() },
            onCancelClick = { cleanup(); switchBackToPreviousKeyboard() })
        statusText = ui.statusText
        recordingPanel = ui.panel
        microphoneSession?.let { ui.panel.bind(serviceScope, it.telemetry.state) }
        return ui.view
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.i(TAG, "onStartInputView, restarting=$restarting")
        if (!restarting && microphoneSession == null) startRecording()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.i(TAG, "onFinishInputView")
        // A new editor must not receive the previous editor's delayed results.
        editorActive = false
        if (isRecording) stopRecordingAndTranscribe()
    }
    override fun onFinishInput() {
        editorActive = false
        if (isRecording) stopRecordingAndTranscribe()
        super.onFinishInput()
    }
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // Same-keyboard focus changes need not close the input view. Field/package
        // identity supplements onFinishInputView so late text cannot migrate.
        if (microphoneSession != null && targetEditor != Pair(info?.packageName, info?.fieldId ?: 0)) {
            editorActive = false
            if (isRecording) stopRecordingAndTranscribe()
        }
    }
    private fun startRecording() {
        Log.i(TAG, "Starting incremental recording")
        isRecording = true; editorActive = true
        statusText?.text = getString(R.string.model_loading)
        val connection = currentInputConnection
        targetEditor = Pair(currentInputEditorInfo?.packageName, currentInputEditorInfo?.fieldId ?: 0)
        val target = StreamingTextTarget(this) { text -> editorActive && connection?.commitText(text, 1) == true }
        microphoneSession = TranslanderApp.instance.microphoneSessions.create(this, serviceScope,
            onText = { delta, _ -> target.accept(delta); statusText?.text = delta.takeLast(100) },
            onComplete = { store, error ->
                isRecording = false; microphoneSession = null
                target.finish(store, preserve = error != null)
                error?.let { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); store?.let { result -> TranscriptRecovery.show(this, result) } }
                if (editorActive) switchBackToPreviousKeyboard()
            }, onCaptureEnded = { isRecording = false; statusText?.text = getString(R.string.state_processing) },
            onWarning = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
            onReady = { statusText?.text = getString(R.string.state_listening) })
        microphoneSession?.let { recordingPanel?.bind(serviceScope, it.telemetry.state); it.start() }
    }
    private fun stopRecordingAndTranscribe() {
        Log.i(TAG, "Stopping recording")
        isRecording = false; statusText?.text = getString(R.string.state_processing)
        microphoneSession?.stop()
    }
    private fun switchBackToPreviousKeyboard() {
        try { @Suppress("DEPRECATION") switchToPreviousInputMethod() }
        catch (e: Exception) { Log.e(TAG, "Failed to switch back", e) }
    }
    private fun cleanup() { editorActive = false; isRecording = false; microphoneSession?.cancel(); microphoneSession = null; recordingPanel?.release() }
}
