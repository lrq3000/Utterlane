package com.translander

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.translander.asr.*
import com.translander.history.HistoryRetention
import com.translander.service.FloatingMicService
import com.translander.service.TextInjectionService
import com.translander.transcribe.AudioDecoder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real services, IPC, editors and Parakeet; deterministic real-time speech replaces only capture. */
@RunWith(AndroidJUnit4::class)
class AudioIntegrationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TranslanderApp
    private lateinit var automation: UiAutomation
    private lateinit var previousFactory: MicrophoneSessionFactory
    private val source = AtomicReference<SpokenCapture>()
    private var oldServices = ""
    private var oldAccessibility = ""
    private var oldIme = ""
    private var oldEnabledImes = ""
    private var oldHardKeyboard = ""
    private val voiceIme = "at.webformat.translander/com.translander.ime.VoiceInputMethodService"

    @Before fun prepare(): Unit = runBlocking {
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        for (permission in listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.RECORD_AUDIO")) shell("pm grant ${app.packageName} $permission")
        shell("pm grant ${instrumentation.context.packageName} android.permission.RECORD_AUDIO")
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        oldServices = shell("settings get secure enabled_accessibility_services")
        oldAccessibility = shell("settings get secure accessibility_enabled")
        oldIme = shell("settings get secure default_input_method")
        oldEnabledImes = shell("settings get secure enabled_input_methods")
        oldHardKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        val directory = File(app.filesDir, "parakeet-v3").apply { mkdirs() }
        for (name in listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")) {
            val destination = File(directory, name)
            if (!destination.exists()) File("/sdcard/Download/parakeet-qa", name).copyTo(destination)
        }
        assertTrue(app.recognizerManager.ensureInitialized())
        app.settingsRepository.setHistoryRetention(HistoryRetention.NONE)
        app.recordingHistory.prune(HistoryRetention.NONE)
        app.getSharedPreferences("transcript_recovery", 0).edit().clear().commit()
        app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("QA", ""))
        val pieces = mutableListOf<ShortArray>()
        AudioDecoder(app).decode("/sdcard/Download/speech-source.wav", { pieces.add(it) })
        val seed = ShortArray(pieces.sumOf { it.size })
        var offset = 0
        for (piece in pieces) { piece.copyInto(seed, offset); offset += piece.size }
        previousFactory = app.microphoneSessions
        app.microphoneSessions = MicrophoneSessionFactory { SpokenCapture(seed).also { source.set(it) } }
        app.startActivity(Intent(app, com.translander.settings.SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("target application launch") { find { it.packageName?.toString() == app.packageName } != null }
    }

    @After fun restore() {
        source.get()?.stop()
        app.stopService(Intent(app, FloatingMicService::class.java))
        if (oldIme.isNotEmpty() && oldIme != "null") shell("ime set $oldIme")
        restoreSetting("enabled_input_methods", oldEnabledImes)
        restoreSetting("show_ime_with_hard_keyboard", oldHardKeyboard)
        restoreSetting("enabled_accessibility_services", oldServices)
        restoreSetting("accessibility_enabled", oldAccessibility)
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        await("microphone cleanup") { !MicrophoneSession.isBusy() }
        if (::previousFactory.isInitialized) app.microphoneSessions = previousFactory
    }

    @Test fun speechRecognizerReturnsLivePartialAndFinalResults() {
        launchEditor(requestSpeechApi = true)
        await("live API partial result") {
            val result = editor("QA result")?.text?.toString().orEmpty()
            if (result == "Error: direct speech binding failed" && shell("getprop ro.build.version.sdk") == "28" &&
                shell("pm path com.ldmnq.launcher3").startsWith("package:")) {
                // Reproduced on the unchanged db74ab2 APK too. Do not silently
                // label this emulator's pre-ASR binding refusal as a passing API test.
                org.junit.Assume.assumeTrue("LDPlayer 9 refuses SpeechRecognizer binding on baseline and updated APKs", false)
            }
            assertFalse("Speech API failed: $result", result.startsWith("Error:"))
            result.startsWith("Partial:") && result.contains("country", true)
        }
        assertTrue(source.get().running.get())
        click(editor("QA stop recognition")!!)
        await("API final result") { editor("QA result")?.text?.toString()?.startsWith("Final:") == true }
        assertTrue(editor("QA result")!!.text.toString().contains("country", true))
    }

    @Test fun voiceActivityPreviewsSpeechAndReturnsFinalCallerResult() {
        val monitor = instrumentation.addMonitor(com.translander.service.VoiceInputActivity::class.java.name, null, false)
        try {
            launchEditor(requestVoice = true)
            val activity = monitor.waitForActivityWithTimeout(10000) as com.translander.service.VoiceInputActivity
            val overlay = com.translander.service.VoiceInputActivity::class.java.getDeclaredField("recordingOverlay").apply { isAccessible = true }.get(activity)
            val view = com.translander.ui.RecordingOverlay::class.java.getDeclaredField("overlayView").apply { isAccessible = true }.get(overlay) as android.view.View
            // LDPlayer omits the non-focusable voice window from its accessibility
            // tree. Read its actual attached view on the UI thread, then invoke
            // the real button listener; caller/result IPC remains unmodified.
            await("live voice-activity preview") {
                var visible = false
                instrumentation.runOnMainSync { visible = view.isAttachedToWindow && view.findViewById<android.widget.TextView>(R.id.recording_status).text.contains("country", true) }
                visible
            }
            assertTrue(source.get().running.get())
            instrumentation.runOnMainSync { view.findViewById<android.widget.Button>(R.id.recording_done).performClick() }
            await("voice activity result") { editor("QA result")?.text?.contains("country", true) == true }
            assertTrue(editor("QA result")!!.text.toString().startsWith("Result -1:"))
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun accessibilityPinsOriginalFieldAndRecoversAfterFocusChange() {
        enableAccessibility()
        launchEditor()
        toggleAccessibility()
        await("accessibility live text") { editor("QA first editor")?.text?.contains("country", true) == true }
        val firstLength = editor("QA first editor")!!.text.length
        assertTrue(source.get().running.get())
        assertTrue(editor("QA second editor")!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        toggleAccessibility()
        await("focus-change recovery") { app.getSharedPreferences("transcript_recovery", 0).getString("path", null) != null }
        assertFalse(editor("QA second editor")!!.text.toString().contains("country", true))
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clipboard.contains("country", true))
        assertTrue(clipboard.length > firstLength)
    }

    @Test fun floatingMicrophoneInsertsLiveTextIntoPinnedEditor() {
        enableAccessibility()
        launchEditor()
        app.startForegroundService(Intent(app, FloatingMicService::class.java))
        await("floating microphone overlay") { find { it.viewIdResourceName?.endsWith("/floating_mic_button") == true } != null }
        tap(find { it.viewIdResourceName?.endsWith("/floating_mic_button") == true }!!)
        await("new floating capture") { source.get()?.running?.get() == true }
        await("floating live text") { editor("QA first editor")?.text?.contains("country", true) == true }
        assertTrue(source.get().running.get())
        tap(find { it.viewIdResourceName?.endsWith("/floating_mic_button") == true }!!)
        await("floating capture stop") { !source.get().running.get() }
    }

    @Test fun voiceImeCommitsLiveTextAndDrainsOnDone() {
        shell("ime enable $voiceIme")
        shell("ime set $voiceIme")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        launchEditor(showKeyboard = true)
        await("new IME capture") { source.get()?.running?.get() == true }
        await("IME live text") { editor("QA first editor")?.text?.contains("country", true) == true }
        assertTrue(source.get().running.get())
        click(find { it.viewIdResourceName?.endsWith("/recording_done") == true }!!)
        await("IME capture stop") { !source.get().running.get() }
        await("previous keyboard restored") { shell("settings get secure default_input_method") != voiceIme }
        assertTrue(editor("QA first editor")!!.text.toString().contains("country", true))
    }

    private fun enableAccessibility() {
        val component = "at.webformat.translander/com.translander.service.TextInjectionService"
        val enabled = oldServices.takeUnless { it == "null" || it.isEmpty() }?.split(':').orEmpty()
        shell("settings put secure enabled_accessibility_services ${(enabled + component).distinct().joinToString(":")}")
        shell("settings put secure accessibility_enabled 1")
        await("accessibility service connection") { TextInjectionService.instance != null }
    }
    private fun toggleAccessibility() {
        // LDPlayer hides the system navigation accessibility button. Exercise its
        // unchanged callback entry point, then real Android node/text delivery.
        instrumentation.runOnMainSync {
            TextInjectionService::class.java.getDeclaredMethod("toggleRecording").apply { isAccessible = true }.invoke(TextInjectionService.instance)
        }
    }
    private fun launchEditor(requestVoice: Boolean = false, showKeyboard: Boolean = false, requestSpeechApi: Boolean = false) {
        app.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, QAEditorActivity::class.java.name))
            .putExtra("request_voice", requestVoice).putExtra("show_keyboard", showKeyboard).putExtra("request_speech_api", requestSpeechApi)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        if (requestVoice) await("new voice activity capture") { source.get()?.running?.get() == true }
        else await("fresh QA editor") { editor("QA first editor")?.text?.toString()?.contains("country", true) == false }
    }
    private fun editor(description: String) = find { it.contentDescription?.toString() == description }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        automation.windows.forEach { it.root?.let(queue::add) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }
    private fun click(node: AccessibilityNodeInfo) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun await(description: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 45000
        while (SystemClock.uptimeMillis() < end) { if (predicate()) return; Thread.sleep(100) }
        fail("Timed out waiting for $description")
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private fun restoreSetting(key: String, value: String) { if (value.isEmpty() || value == "null") shell("settings delete secure $key") else shell("settings put secure $key $value") }

    private class SpokenCapture(private val seed: ShortArray) : AudioCapture {
        val running = AtomicBoolean(false)
        private val stopped = AtomicBoolean(false)
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            var offset = 0
            running.set(true)
            try {
                while (!stopped.get() && shouldContinue()) {
                    onSamples(ShortArray(3200) { seed[(offset + it) % seed.size] })
                    offset = (offset + 3200) % seed.size
                    Thread.sleep(200)
                }
            } finally { running.set(false) }
        }
        override fun stop() { stopped.set(true) }
    }
}
