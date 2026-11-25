package io.github.lrq3000.utterlane

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.asr.MicrophoneSessionFactory
import io.github.lrq3000.utterlane.ime.VoiceInputMethodService
import io.github.lrq3000.utterlane.ui.theme.BlueHarmony
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class VoiceImeSpeakerVisualAndroidTest {
    @Test fun captureTheActualKeyboardWindow() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        assumeTrue(app.packageName.endsWith(".qolsandbox") && InstrumentationRegistry.getArguments().getString("speakerVisualEvidence") == "true")
        val ui = OnboardingTestUi()
        ui.prepare()
        val settings = app.settingsRepository
        val priorLabels = settings.diarizationEnabled.first()
        val priorTheme = settings.themeMode.first()
        val priorFactory = app.microphoneSessions
        val fixture = OnboardingRecognitionAndroidTest.FixtureCapture(ShortArray(16000) { 500 })
        val ids = app.recordingHistory.list().map { it.id }.toSet()
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString().trim() }
        val prior = listOf("default_input_method", "enabled_input_methods", "show_ime_with_hard_keyboard")
            .associateWith { shell("settings get secure $it") }
        val ime = ComponentName(app, VoiceInputMethodService::class.java).flattenToShortString()
        try {
            settings.setThemeMode("dark")
            settings.setDiarizationEnabled(false)
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.RECORD_AUDIO)
            app.microphoneSessions = MicrophoneSessionFactory { fixture }
            app.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, QAEditorActivity::class.java.name))
                .putExtra("show_keyboard", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            fun nodes() = instrumentation.uiAutomation.windows.mapNotNull { it.root }
            check(withTimeoutOrNull(10000) {
                while (nodes().none { it.packageName == instrumentation.context.packageName }) delay(100)
                true
            } == true) { "QA editor did not acquire a window" }
            shell("ime enable $ime")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("settings put secure default_input_method $ime")
            val id = "${app.packageName}:id/recording_speakers"
            for (checked in listOf(false, true)) {
                fun find() = nodes().flatMap { it.findAccessibilityNodeInfosByViewId(id) }.firstOrNull { it.isVisibleToUser }
                val node = checkNotNull(withTimeoutOrNull(15000) {
                    var result = find()
                    while (result == null) { delay(100); result = find() }
                    result
                }) { "Missing IME button. Windows=${nodes().map { it.packageName }}; ${shell("logcat -d -s VoiceInputMethodService:I").takeLast(1200)}" }
                if (checked) check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                check(withTimeoutOrNull(5000) { while (find()?.isChecked != checked) delay(50); true } == true)
                // A missing-model notice moves the whole IME above its previous screen coordinates.
                // Reacquire the node after the state/layout change; the pre-tap rectangle is not current.
                node.recycle()
                verifyKeyboardSpeaker("ime-dark-$checked", {
                    find()?.let { current -> try { Rect().also(current::getBoundsInScreen) } finally { current.recycle() } }
                }, if (checked) BlueHarmony.Dark.primary.toArgb() else 0xC7FFFFFF.toInt())
            }
        } finally {
            val oldIme = prior.getValue("default_input_method")
            if (oldIme !in listOf("", "null")) shell("ime set $oldIme")
            for ((key, value) in prior) {
                if (value in listOf("", "null")) shell("settings delete secure $key") else shell("settings put secure $key $value")
            }
            fixture.stop()
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            withTimeoutOrNull(15000) { while (MicrophoneSession.isBusy()) delay(50) }
            app.microphoneSessions = priorFactory
            settings.setDiarizationEnabled(priorLabels)
            settings.setThemeMode(priorTheme)
            app.recordingHistory.list().filter { it.id !in ids }.forEach { app.recordingHistory.delete(it.id) }
        }
    }
}
