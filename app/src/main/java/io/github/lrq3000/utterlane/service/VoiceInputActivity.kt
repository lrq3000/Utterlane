package io.github.lrq3000.utterlane.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.ui.RecordingOverlay
import kotlinx.coroutines.*

/** Final-result voice-input contract with live preview and no ten-second recording cutoff. */
class VoiceInputActivity : io.github.lrq3000.utterlane.settings.LocalizedActivity() {
    companion object { private const val TAG = "VoiceInputActivity" }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var microphoneSession: MicrophoneSession? = null
    private var isRecording = false
    private var recordingOverlay: RecordingOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceInputActivity created")
        // System overlay avoids stealing editor focus from the calling application.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (isRecording) stopRecording() else { setResult(RESULT_CANCELED); finish() } }
        })
        if (!Settings.canDrawOverlays(this)) { fail(getString(R.string.toast_overlay_required)); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(getString(R.string.toast_mic_required)); return
        }
        recordingOverlay = RecordingOverlay(this).apply {
            onDoneClick = { if (isRecording) stopRecording() }
            onCancelClick = { cleanup(); setResult(RESULT_CANCELED); finish() }
        }
        recordingOverlay?.show()
        recordingOverlay?.setStatus(getString(R.string.model_loading))
        isRecording = true
        microphoneSession = UtterlaneApp.instance.microphoneSessions.create(this, activityScope,
            onText = { delta, _ -> recordingOverlay?.setStatus(delta.takeLast(150)) },
            onComplete = { store, error ->
                isRecording = false; microphoneSession = null
                TranscriptFinalization.deliver(this, store) {
                    val text = withContext(Dispatchers.IO) { store?.readForTransfer() }
                    if (isFinishing || isDestroyed) return@deliver false
                    if (error != null) { fail(error.message); false }
                    else if (text.isNullOrBlank()) {
                        fail(getString(if (text == null) R.string.stream_use_export else R.string.toast_no_speech))
                        false
                    } else {
                        Log.i(TAG, "Returning completed transcription: ${text.length} characters")
                        setResult(RESULT_OK, Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text)))
                        finish()
                        true
                    }
                }
            }, onCaptureEnded = { isRecording = false; recordingOverlay?.setStatus(getString(R.string.state_processing)) },
            onWarning = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
            onReady = { recordingOverlay?.setStatus(getString(R.string.state_listening)) })
        microphoneSession?.let { recordingOverlay?.bind(activityScope, it); it.start() }
    }
    private fun stopRecording() { Log.i(TAG, "Stopping recording"); isRecording = false; microphoneSession?.stop(); recordingOverlay?.setStatus(getString(R.string.state_processing)) }
    private fun fail(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); setResult(RESULT_CANCELED); finish() }
    private fun cleanup() { recordingOverlay?.hide(); recordingOverlay = null; microphoneSession?.cancel(); microphoneSession = null }
    override fun onDestroy() { super.onDestroy(); cleanup(); activityScope.cancel() }
}
