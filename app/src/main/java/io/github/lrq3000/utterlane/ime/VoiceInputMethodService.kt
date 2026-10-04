package io.github.lrq3000.utterlane.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.asr.DeviceWakeObserver
import android.view.inputmethod.InputConnection
import io.github.lrq3000.utterlane.service.StreamingTextTarget
import io.github.lrq3000.utterlane.service.TranscriptRecovery
import io.github.lrq3000.utterlane.ui.RecordingUIBuilder
import kotlinx.coroutines.*

/** Auxiliary voice IME. Shared recording UI and bounded microphone pipeline. */
class VoiceInputMethodService : InputMethodService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(io.github.lrq3000.utterlane.settings.AppLanguage.wrap(base))
    companion object { private const val TAG = "VoiceInputMethodService" }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var microphoneSession: MicrophoneSession? = null
    private var isRecording = false
    private var editorActive = false
    private var targetEditor: Pair<String?, Int>? = null
    private var targetConnection: InputConnection? = null
    private var textTarget: StreamingTextTarget? = null
    private var hiddenJob: Job? = null
    private var statusText: TextView? = null
    private var recordingPanel: io.github.lrq3000.utterlane.ui.RecordingPanel? = null

    override fun onCreate() { super.onCreate(); Log.i(TAG, "VoiceInputMethodService created") }
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VoiceInputMethodService destroyed")
        // UI teardown is not the user's Cancel action. Drain captured audio on
        // the application scope and recover it if this editor no longer exists.
        editorActive = false
        hiddenJob?.cancel()
        microphoneSession?.stop()
        textTarget?.close()
        recordingPanel?.release()
        serviceScope.cancel()
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
        Log.i(TAG, "onStartInputView, restarting=$restarting, editor=${info?.packageName}/${info?.fieldId}, sameConnection=${currentInputConnection === targetConnection}")
        hiddenJob?.cancel()
        if (microphoneSession != null) {
            val sameEditor = targetEditor == Pair(info?.packageName, info?.fieldId ?: 0)
            // Resource IDs are reused by different activities/conversations. A
            // matching ID alone must never authorize rebinding pending text to a
            // new connection. Keep transcribing into recovery when uncertain.
            editorActive = sameEditor && targetConnection != null && currentInputConnection === targetConnection
            if (editorActive) textTarget?.resume()
            else if (!sameEditor && DeviceWakeObserver.canDeliver(this) && isRecording) stopRecordingAndTranscribe()
        } else if (!restarting) startRecording()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.i(TAG, "onFinishInputView")
        // A new editor must not receive the previous editor's delayed results.
        inputHidden()
    }
    override fun onFinishInput() {
        inputHidden()
        super.onFinishInput()
    }
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        Log.i(TAG, "onStartInput, restarting=$restarting, editor=${info?.packageName}/${info?.fieldId}, target=$targetEditor, interactive=${DeviceWakeObserver.isInteractive(this)}")
        // Same-keyboard focus changes need not close the input view. Field/package
        // identity supplements onFinishInputView so late text cannot migrate.
        if (microphoneSession != null && targetEditor != Pair(info?.packageName, info?.fieldId ?: 0)) {
            editorActive = false
            if (DeviceWakeObserver.canDeliver(this) && isRecording) stopRecordingAndTranscribe()
        }
    }

    private fun inputHidden() {
        editorActive = false
        hiddenJob?.cancel()
        hiddenJob = serviceScope.launch {
            // Window teardown can precede SCREEN_OFF. Classify after the power
            // transition settles; never stop a live session merely for sleeping.
            delay(300)
            if (DeviceWakeObserver.canDeliver(this@VoiceInputMethodService) && isRecording) stopRecordingAndTranscribe()
        }
    }
    private fun startRecording() {
        Log.i(TAG, "Starting incremental recording")
        isRecording = true; editorActive = true
        statusText?.text = getString(R.string.model_loading)
        targetConnection = currentInputConnection
        val connection = targetConnection
        targetEditor = Pair(currentInputEditorInfo?.packageName, currentInputEditorInfo?.fieldId ?: 0)
        lateinit var target: StreamingTextTarget
        target = StreamingTextTarget(this, available = { editorActive && textTarget === target }) { text -> connection?.commitText(text, 1) == true }
        textTarget = target
        microphoneSession = UtterlaneApp.instance.microphoneSessions.create(this, UtterlaneApp.instance.applicationScope,
            onText = { delta, store -> target.accept(store); statusText?.text = delta.takeLast(100) },
            onComplete = { store, error ->
                isRecording = false; microphoneSession = null
                target.finish(store, preserve = error != null)
                error?.let { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); store?.let { result -> TranscriptRecovery.show(this, result) } }
                if (editorActive) switchBackToPreviousKeyboard()
            }, onCaptureEnded = { isRecording = false; statusText?.text = getString(R.string.state_processing) },
            onWarning = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
            onReady = { statusText?.text = getString(R.string.state_listening) },
            onSessionClosed = { target.sessionClosed() })
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
    private fun cleanup() {
        hiddenJob?.cancel(); editorActive = false; isRecording = false
        textTarget?.close(); textTarget = null; targetConnection = null
        microphoneSession?.cancel(); microphoneSession = null; recordingPanel?.release()
    }
}
