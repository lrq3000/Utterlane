package io.github.lrq3000.utterlane.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class TextInjectionService : AccessibilityService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(io.github.lrq3000.utterlane.settings.AppLanguage.wrap(base))

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var microphoneSession: MicrophoneSession? = null
    private var recordingOverlay: io.github.lrq3000.utterlane.ui.RecordingOverlay? = null
    private val isRecording = AtomicBoolean(false)

    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")

        // Register accessibility button callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    Log.i(TAG, "Accessibility button clicked")
                    toggleRecording()
                }

                override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                    Log.i(TAG, "Accessibility button availability: $available")
                }
            }
            accessibilityButtonCallback?.let { callback ->
                accessibilityButtonController.registerAccessibilityButtonCallback(callback)
            }
        }
    }

    private fun initializeRecognizer() {
        Log.i(TAG, "initializeRecognizer called")
        serviceScope.launch(Dispatchers.IO) {
            val recognizerManager = UtterlaneApp.instance.recognizerManager
            val modelManager = UtterlaneApp.instance.modelManager
            Log.i(TAG, "Model ready: ${modelManager.isModelReady()}, recognizer ready: ${recognizerManager.isInitialized()}")

            if (!modelManager.isModelReady()) {
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.toast_model_not_downloaded))
                }
                return@launch
            }

            if (!recognizerManager.isInitialized()) {
                val success = recognizerManager.initialize()
                withContext(Dispatchers.Main) {
                    if (success) {
                        showToast(getString(R.string.toast_voice_ready))
                    } else {
                        showToast(getString(R.string.toast_model_load_failed))
                    }
                }
            }
        }
    }

    private fun toggleRecording() {
        Log.i(TAG, "toggleRecording called, isRecording=${isRecording.get()}")
        if (isRecording.get()) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (microphoneSession != null) { showToast(getString(R.string.state_processing)); return }
        if (MicrophoneSession.isBusy()) { showToast(getString(R.string.stream_busy)); return }
        // Check mic permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Microphone permission not granted")
            showToast(getString(R.string.toast_mic_required_open_app))
            return
        }

        val recognizerManager = UtterlaneApp.instance.recognizerManager
        Log.i(TAG, "startRecording called, recognizer ready=${recognizerManager.isInitialized()}")

        if (!recognizerManager.isInitialized()) {
            Log.i(TAG, "Capture session will initialize the selected model")
        }

        isRecording.set(true)
        showToast(getString(R.string.state_recording))

        val target = captureStreamingTarget()
        recordingOverlay = io.github.lrq3000.utterlane.ui.RecordingOverlay(this).apply {
            onDoneClick = { if (isRecording.get()) stopRecording() }
            onCancelClick = {
                target.close()
                microphoneSession?.cancel(); microphoneSession = null; isRecording.set(false)
                hideCapturePanel()
            }
            show()
        }
        microphoneSession = UtterlaneApp.instance.microphoneSessions.create(this, serviceScope,
            onText = { delta, store -> target.accept(store); recordingOverlay?.setStatus(delta) },
            onComplete = { store, error ->
                isRecording.set(false); microphoneSession = null
                hideCapturePanel()
                target.finish(store, preserve = error != null)
                error?.let { showToast(it.message); store?.let { result -> TranscriptRecovery.show(this, result) } }
            }, onCaptureEnded = { isRecording.set(false) }, onWarning = { showToast(it) },
            onSessionClosed = { target.sessionClosed() })
        Log.i(TAG, "Starting incremental audio recording")
        microphoneSession?.let { recordingOverlay?.bind(serviceScope, it); it.start() }
    }

    private fun stopRecording() {
        Log.i(TAG, "stopRecording called")
        isRecording.set(false)
        showToast(getString(R.string.state_processing))

        microphoneSession?.stop()
    }

    companion object {
        private const val TAG = "TextInjectionService"
        @Volatile
        var instance: TextInjectionService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    fun captureStreamingTarget(): StreamingTextTarget {
        val node = findFocusedEditText()
        return StreamingTextTarget(this) { delta ->
            // A saved node may still report focus in a background window. Check
            // the active window too, and never reacquire an unrelated text field.
            node != null && rootInActiveWindow?.windowId == node.windowId &&
                node.refresh() && node.isFocused && node.isEditable && insertTextIntoNode(node, delta)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events, just inject text
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonCallback?.let { callback ->
                accessibilityButtonController.unregisterAccessibilityButtonCallback(callback)
            }
        }
        microphoneSession?.cancel()
        microphoneSession = null
        hideCapturePanel()
        serviceScope.cancel()
        instance = null
    }

    fun injectText(text: String) {
        Log.i(TAG, "injectText called with: $text")
        val focusedNode = findFocusedEditText()
        if (focusedNode != null) {
            Log.i(TAG, "Found focused node, injecting text")
            insertTextIntoNode(focusedNode, text)
        } else {
            Log.w(TAG, "No focused text field found, copying to clipboard")
            showToast(getString(R.string.toast_no_field_clipboard))
            copyToClipboard(text)
        }
    }

    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "rootInActiveWindow is null")
            return null
        }

        // First try to find input-focused editable node
        val inputFocused = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocused != null && inputFocused.isEditable) {
            Log.i(TAG, "Found input-focused editable node")
            return inputFocused
        }

        // Fallback to searching the tree
        return findFocusedNode(rootNode)
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) {
            Log.i(TAG, "Found focused editable node: ${node.className}")
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedNode(child)
            if (result != null) {
                return result
            }
        }
        return null
    }

    private fun insertTextIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        // Get current text - but check if it's just placeholder/hint text
        val rawText = node.text?.toString() ?: ""
        val hintText = node.hintText?.toString() ?: ""

        // If current text equals hint text, field is empty (showing placeholder)
        val currentText = if (rawText == hintText || rawText.isEmpty()) "" else rawText

        Log.d(TAG, "Current text: '$currentText', hint: '$hintText', raw: '$rawText'")

        // Try to get selection/cursor position
        val selectionStart = if (node.textSelectionStart >= 0 && currentText.isNotEmpty())
            node.textSelectionStart else currentText.length
        val selectionEnd = if (node.textSelectionEnd >= 0 && currentText.isNotEmpty())
            node.textSelectionEnd else selectionStart

        // Build new text with insertion
        val newText = if (currentText.isEmpty()) {
            text  // Just set the text directly if field is empty
        } else {
            StringBuilder(currentText)
                .replace(selectionStart, selectionEnd, text)
                .toString()
        }

        // Try ACTION_SET_TEXT first
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            newText
        )
        val setTextSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "ACTION_SET_TEXT result: $setTextSuccess")

        // If ACTION_SET_TEXT failed or isn't supported, try clipboard paste fallback
        if (!setTextSuccess) {
            Log.d(TAG, "ACTION_SET_TEXT failed, trying clipboard paste fallback")
            return tryClipboardPaste(node, text)
        }

        // Move cursor to end of inserted text
        val newCursorPosition = if (currentText.isEmpty()) text.length else selectionStart + text.length
        val selectionArgs = Bundle()
        selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPosition)
        selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPosition)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        return true
    }

    private fun tryClipboardPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        // Save current clipboard content
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val oldClip = clipboard.primaryClip

        // Set our text to clipboard
        val clip = android.content.ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)

        // Try to paste
        val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "ACTION_PASTE result: $pasteSuccess")

        // Restore old clipboard after a short delay
        if (oldClip != null) {
            serviceScope.launch {
                delay(500)
                clipboard.setPrimaryClip(oldClip)
            }
        }

        if (!pasteSuccess) {
            showToast(getString(R.string.toast_copied_clipboard_paste))
        }
        return pasteSuccess
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)
    }

    fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    private fun hideCapturePanel() { recordingOverlay?.hide(); recordingOverlay = null }
}
