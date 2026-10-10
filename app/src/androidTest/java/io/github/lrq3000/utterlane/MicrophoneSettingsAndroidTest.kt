package io.github.lrq3000.utterlane

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.audio.*
import io.github.lrq3000.utterlane.history.RecordingRecovery
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run serially on the primary agent's isolated .micui QA installation. These are
 * real settings-window tests; the diagnostic session is synthetic, not evidence
 * of physical HFP capture. No installed model or microphone permission is needed.
 *
 * Run deniedDefaultDoesNotPromptAndExplicitActionCanRecover first on API 31+ with
 * BLUETOOTH_CONNECT revoked outside instrumentation. It deliberately grants access
 * on return from App settings. The other test grants access to exercise selectors
 * without a system dialog obscuring them. Neither test revokes its own process.
 */
@RunWith(AndroidJUnit4::class)
class MicrophoneSettingsAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val settings get() = app.settingsRepository
    private val ui = OnboardingTestUi()

    @Test fun presetsCustomControlsAndDiagnosticsStayIndependent() = runBlocking {
        prepare()
        if (Build.VERSION.SDK_INT >= 31) instrumentation.uiAutomation.grantRuntimePermission(
            app.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        val previous = settings.microphoneSettings.first()
        var activity: SettingsActivity? = null
        val session = app.microphoneDiagnostics.begin(MicrophoneOptions.HFP)
        session.route("Synthetic UI diagnostic AA:BB:CC:DD:EE:FF")
        try {
            settings.resetMicrophoneOptions()
            activity = open()
            selectPreset(MicrophonePreset.DISABLED, R.string.microphone_processing_disabled)
            assertEquals(MicrophoneOptions.STANDARD, settings.microphoneSettings.first().options)
            selectPreset(MicrophonePreset.CUSTOM, R.string.microphone_processing_custom)
            assertEquals("Custom entry must retain the complete Disabled tuple",
                MicrophoneOptions.STANDARD, settings.microphoneSettings.first().options)
            // Start away from each Disabled value: Compose correctly omits the
            // accessibility click action on the already-selected radio option.
            // Reverse order exercises an actual transition to every backend value.
            for (source in MicrophoneSource.entries.reversed()) {
                choose("microphone_source", source.name)
                await { settings.microphoneSettings.first().options.source == source }
            }
            for (route in BluetoothCaptureRoute.entries.reversed()) {
                choose("microphone_route", route.name)
                await { settings.microphoneSettings.first().options.route == route }
            }
            for (mode in BluetoothAudioMode.entries.reversed()) {
                choose("microphone_mode", mode.name)
                await { settings.microphoneSettings.first().options.mode == mode }
            }
            for (policy in InputPreprocessingPolicy.entries.reversed()) {
                choose("microphone_preprocessing", policy.name)
                await { settings.microphoneSettings.first().options.preprocessing == policy }
            }
            val gainLabels = listOf(R.string.microphone_processing_gain_off, R.string.microphone_processing_gain_6,
                R.string.microphone_processing_gain_12, R.string.microphone_processing_gain_18,
                R.string.microphone_processing_gain_auto)
            for (gain in PcmGainMode.entries.reversed()) {
                choose("microphone_gain", app.getString(gainLabels[gain.ordinal]))
                await { settings.microphoneSettings.first().options.gain == gain }
            }
            assertEquals("Editing next-recording settings must not relabel a live session",
                MicrophoneOptions.HFP, app.microphoneDiagnostics.state.value.options)
            val custom = settings.microphoneSettings.first()
            ui.scrollTo("microphone_custom_controls", backwards = true)
            ui.click("microphone_custom_controls")
            assertFalse(ui.hasVisibleText(app.getString(R.string.microphone_processing_source)))
            ui.click("microphone_diagnostics")
            ui.click("microphone_diagnostics_copy")
            val diagnosticText = app.microphoneDiagnostics.state.value.text()
            assertTrue(diagnosticText.contains("[redacted]"))
            assertFalse(diagnosticText.contains("AA:BB:CC:DD:EE:FF"))
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            instrumentation.runOnMainSync {
                assertEquals(diagnosticText, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
            assertEquals(custom, settings.microphoneSettings.first())
            ui.screenshot("microphone-custom-collapsed-diagnostics")

            session.finish()
            assertFalse(app.microphoneDiagnostics.state.value.active)
            ui.scrollTo("microphone_reset", backwards = true)
            ui.click("microphone_reset")
            await { settings.microphoneSettings.first() == MicrophoneSettings() }
            // Diagnostics stay open when switching presets, including Disabled.
            ui.scrollTo("microphone_preset", backwards = true)
            selectPreset(MicrophonePreset.DISABLED, R.string.microphone_processing_disabled)
            ui.scrollTo("microphone_diagnostics_text")
            assertTrue(ui.hasVisibleText(app.microphoneDiagnostics.state.value.text()))
            ui.scrollTo("microphone_preset", backwards = true)
            selectPreset(MicrophonePreset.HFP_PRESET, R.string.microphone_processing_hfp)
            assertEquals(MicrophoneOptions.HFP, settings.microphoneSettings.first().options)
            ui.scrollTo("microphone_preset", backwards = true)
            selectPreset(MicrophonePreset.CUSTOM, R.string.microphone_processing_custom)
            ui.scrollTo("microphone_source")
            assertTrue("Re-entering Custom expands its controls",
                ui.hasVisibleText(app.getString(R.string.microphone_processing_source)))

            val closed = activity!!
            instrumentation.runOnMainSync { closed.finish() }
            await { closed.isDestroyed }
            activity = open()
            ui.scrollTo("microphone_source")
            assertEquals(MicrophoneSettings(MicrophonePreset.CUSTOM, MicrophoneOptions.HFP), settings.microphoneSettings.first())
        } finally {
            session.finish()
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            restore(previous)
        }
    }

    @Test fun deniedDefaultDoesNotPromptAndExplicitActionCanRecover() = runBlocking {
        prepare()
        assumeTrue("Permission dialog coverage requires Android 12+", Build.VERSION.SDK_INT >= 31)
        assumeTrue("Revoke Nearby devices before this invocation, not inside its process",
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        val previous = settings.microphoneSettings.first()
        val previousInput = settings.audioInputPreferences.first()
        var activity: SettingsActivity? = null
        try {
            settings.resetMicrophoneOptions()
            settings.updateAudioInput { it.copy(preferBluetooth = false) }
            activity = open()
            ui.scrollTo("microphone_hfp_grant")
            assertFalse("Composing the HFP default must not prompt", hasPermissionDialog())
            // Reopen at the top rather than relying on a portrait-only scroll offset
            // to reach the automatic preference row above the processing controls.
            val inputLabel = app.getString(R.string.audio_input_prefer_bluetooth)
            val closed = activity!!
            instrumentation.runOnMainSync { closed.finish() }
            await { closed.isDestroyed }
            activity = open()
            // The label and its switch are siblings, not a clickable settings row.
            // Target the switch's accessible name instead of walking label parents.
            ui.clickDescription(inputLabel)
            await { settings.audioInputPreferences.first().preferBluetooth }
            denyPermissionDialog()
            ui.scrollTo("microphone_hfp_grant")
            assertTrue(settings.audioInputPreferences.first().preferBluetooth)
            val denied = ui.textNode(app.getString(R.string.microphone_hfp_permission_denied))
            @Suppress("DEPRECATION") denied.recycle()
            ui.clickText(app.getString(R.string.microphone_hfp_app_settings))
            // Grant while the app is paused to prove its ON_RESUME refresh path.
            await { instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                try { root.packageName?.toString() == "com.android.settings" }
                finally { @Suppress("DEPRECATION") root.recycle() }
            } == true }
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.BLUETOOTH_CONNECT)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            val granted = ui.textNode(app.getString(R.string.microphone_hfp_permission_granted))
            @Suppress("DEPRECATION") granted.recycle()
            assertTrue(settings.audioInputPreferences.first().preferBluetooth)
            ui.screenshot("microphone-hfp-permission-return")
        } finally {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            restore(previous)
            settings.updateAudioInput { previousInput }
        }
    }

    private fun prepare() {
        assertTrue("Use an isolated microphone/combined ports QA application",
            app.packageName.endsWith(".micui") || app.packageName.endsWith(".audiorecorderports"))
        ui.prepare()
    }

    private fun open(): SettingsActivity {
        val screen = instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java)
            .putExtra(RecordingRecovery.EXTRA_MODELS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        val dialog = ui.textNode(app.getString(R.string.model_choose))
        @Suppress("DEPRECATION") dialog.recycle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        return screen
    }

    private suspend fun selectPreset(preset: MicrophonePreset, label: Int) {
        choose("microphone_preset", app.getString(label))
        await { settings.microphoneSettings.first().preset == preset }
    }

    private suspend fun choose(tag: String, label: String) {
        ui.click(tag)
        val cancel = ui.textNode(app.getString(R.string.action_cancel))
        val windowId = cancel.windowId
        @Suppress("DEPRECATION") cancel.recycle()
        // Scope refreshed nodes to the popup, not the same value in its backdrop.
        ui.clickText(label, windowId)
        await { instrumentation.uiAutomation.windows.none { it.id == windowId } }
    }

    private suspend fun restore(previous: MicrophoneSettings) {
        settings.updateMicrophoneOptions { previous.options }
        settings.setMicrophonePreset(previous.preset)
    }

    private fun permissionButton(): AccessibilityNodeInfo? {
        for (window in instrumentation.uiAutomation.windows) {
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString()?.endsWith("permissioncontroller") == true) {
                    find(root) { it.viewIdResourceName?.endsWith(":id/permission_deny_button") == true }?.let { return it }
                }
            } finally { @Suppress("DEPRECATION") root.recycle() }
        }
        return null
    }

    private fun hasPermissionDialog(): Boolean = permissionButton()?.let {
        @Suppress("DEPRECATION") it.recycle()
        true
    } ?: false

    private suspend fun denyPermissionDialog() {
        await { hasPermissionDialog() }
        clickAncestor(checkNotNull(permissionButton()))
        await { !hasPermissionDialog() }
    }

    private fun clickAncestor(start: AccessibilityNodeInfo) {
        var node = start
        try {
            while (!node.isClickable) {
                val parent = checkNotNull(node.parent)
                @Suppress("DEPRECATION") node.recycle()
                node = parent
            }
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally { @Suppress("DEPRECATION") node.recycle() }
    }

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { find(child, predicate)?.let { return it } }
            finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return null
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(50) }
}
