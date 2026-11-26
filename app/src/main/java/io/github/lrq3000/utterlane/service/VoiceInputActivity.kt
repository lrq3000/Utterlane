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
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.ui.RecordingOverlay
import io.github.lrq3000.utterlane.settings.openSettingsFromDictation
import io.github.lrq3000.utterlane.settings.speakerSettingsAction
import kotlinx.coroutines.*

/** Final-result voice-input contract with live preview and no ten-second recording cutoff. */
class VoiceInputActivity : io.github.lrq3000.utterlane.settings.LocalizedActivity() {
    companion object { private const val TAG = "VoiceInputActivity" }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var microphoneSession: MicrophoneSession? = null
    private var isRecording = false
    private var recordingOverlay: RecordingOverlay? = null
    private var queueHandoff: KeyboardQueueHandoff? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceInputActivity created")
        // System overlay avoids stealing editor focus from the calling application.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (recordingOverlay?.dismissSettingsPrompt() == true || queueHandoff?.ownsNavigation == true) return
                if (isRecording) stopRecording() else { setResult(RESULT_CANCELED); finish() }
            }
        })
        if (!Settings.canDrawOverlays(this)) { fail(getString(R.string.toast_overlay_required)); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(getString(R.string.toast_mic_required)); return
        }
        recordingOverlay = RecordingOverlay(this).apply {
            onDoneClick = { if (isRecording) stopRecording() }
            onCancelClick = {
                if (queueHandoff?.ownsNavigation != true) { cleanup(); setResult(RESULT_CANCELED); finish() }
            }
            onSettingsClick = ::confirmSettings
        }
        recordingOverlay?.show()
        recordingOverlay?.setStatus(getString(R.string.model_loading))
        isRecording = true
        val handoff = try { KeyboardQueueHandoff.create(this) }
        catch (error: Exception) { fail(getString(R.string.dictation_settings_queue_failed)); return }
        queueHandoff = handoff
        microphoneSession = UtterlaneApp.instance.microphoneSessions.create(this, handoff.producerScope,
            onText = { delta, _ -> recordingOverlay?.setStatus(delta.takeLast(150)) },
            onComplete = { store, error ->
                val session = microphoneSession
                val openSettings = session?.speakerControl?.speakerSettingsAction(this)
                isRecording = false; microphoneSession = null
                handoff.complete(store, error?.message, session?.speakerControl?.retryPreference?.value) {
                    if (error != null || store == null) finishForegroundDictation(this, session?.audioId, error)
                    TranscriptFinalization.deliver(this, store, afterDelivery = openSettings) {
                        val text = withContext(Dispatchers.IO) { store?.readForTransfer() }
                        if (isFinishing || isDestroyed) return@deliver false
                        if (error != null) { fail(error.message); false }
                        else if (text.isNullOrBlank()) {
                            fail(getString(if (text == null) R.string.stream_use_export else R.string.toast_no_speech))
                            false
                        } else {
                            Log.i(TAG, "Returning completed transcription: ${text.length} characters")
                            setResult(RESULT_OK, Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text)))
                            finishForegroundDictation(this, session?.audioId, null)
                            finish()
                            true
                        }
                    }
                }
            }, onCaptureEnded = { isRecording = false; recordingOverlay?.setStatus(getString(R.string.state_processing)) },
            onWarning = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
            onReady = { recordingOverlay?.setStatus(getString(R.string.state_listening)) },
            onSessionClosed = { handoff.interrupted(getString(R.string.stream_cancelled)); microphoneSession = null },
            keepResultAudio = true, openRecoveryOnFailure = false)
        microphoneSession?.let { recordingOverlay?.bind(activityScope, it); it.start() }
    }
    private fun confirmSettings(phase: CapturePhase) {
        val session = microphoneSession ?: return
        val handoff = queueHandoff ?: return
        if (session.metrics.state.value.phase != phase || handoff.ownsNavigation) return
        when (phase) {
            CapturePhase.CAPTURING -> {
                if (session.discardWhileCapturing()) {
                    isRecording = false; microphoneSession = null
                    openSettingsAndClose()
                }
            }
            CapturePhase.PROCESSING -> session.audioId?.takeIf { session.captureReleased }?.let { audio ->
                handoff.submit(audio, session.speakerControl.retryPreference.value == true, ::openSettingsAndClose) {
                    notifyDictationQueueFailure(this, it)
                }
            }
            else -> Unit
        }
    }

    private fun openSettingsAndClose() {
        if (isFinishing || isDestroyed) return
        recordingOverlay?.hide(); recordingOverlay = null
        openSettingsFromDictation(this)
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun stopRecording() { Log.i(TAG, "Stopping recording"); isRecording = false; microphoneSession?.stop(); recordingOverlay?.setStatus(getString(R.string.state_processing)) }
    private fun fail(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); setResult(RESULT_CANCELED); finish() }
    private fun cleanup(discard: Boolean = true) {
        recordingOverlay?.hide(); recordingOverlay = null
        if (queueHandoff?.ownsNavigation != true) {
            microphoneSession?.cancel(discard)
            microphoneSession = null
        }
    }
    override fun onDestroy() { super.onDestroy(); cleanup(discard = isFinishing); activityScope.cancel() }
}
