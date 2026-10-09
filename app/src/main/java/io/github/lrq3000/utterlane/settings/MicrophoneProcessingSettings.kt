package io.github.lrq3000.utterlane.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.audio.*
import kotlinx.coroutines.flow.first

/** Foreground settings actions only: a stored HFP default must never open a permission dialog. */
internal class MicrophoneSettingsActions(
    private val settings: SettingsRepository,
    private val inputs: AudioInputController,
    private val requestHfpPermission: () -> Unit,
) {
    suspend fun selectPreset(preset: MicrophonePreset) {
        settings.setMicrophonePreset(preset)
        if (preset == MicrophonePreset.HFP_PRESET) requestIfHfpConfigured()
    }

    suspend fun updateOptions(change: (MicrophoneOptions) -> MicrophoneOptions) =
        settings.updateMicrophoneOptions(change)

    suspend fun selectRoute(route: BluetoothCaptureRoute) {
        updateOptions { it.copy(route = route) }
        if (route == BluetoothCaptureRoute.HFP_VOICE_RECOGNITION) requestIfHfpConfigured()
    }

    suspend fun reset() {
        settings.resetMicrophoneOptions()
        requestIfHfpConfigured()
    }

    suspend fun selectInput(input: AudioInput): Boolean {
        val selected = inputs.select(input.key)
        if (selected && input.bluetooth) requestIfHfpConfigured()
        return selected
    }

    suspend fun setPreferBluetooth(enabled: Boolean) {
        inputs.setPreferBluetooth(enabled)
        if (enabled) requestIfHfpConfigured()
    }

    private suspend fun requestIfHfpConfigured() {
        // A picker write can suspend while another action changes the route. Read
        // the committed snapshot here, not the Compose value from the earlier tap.
        // Denial never rolls back the independent input or automatic preference.
        if (settings.microphoneSettings.first().options.route == BluetoothCaptureRoute.HFP_VOICE_RECOGNITION) {
            requestHfpPermission()
        }
    }
}

/** Permission checks are side-effect free; only requestIfNeeded may launch the system UI. */
internal class HfpPermissionState(private val context: Context) {
    var granted by mutableStateOf(hasPermission())
        private set
    var denied by mutableStateOf(false)
        private set

    private fun hasPermission() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        granted = hasPermission()
        if (granted) denied = false
    }

    fun onResult(granted: Boolean) {
        denied = !granted
        refresh()
    }

    fun requestIfNeeded(launch: (String) -> Unit) {
        refresh()
        if (!granted) launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
}

/** Advanced tuning belongs to the next recording; live diagnostics describe the frozen session. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MicrophoneProcessingSettings(
    repository: SettingsRepository,
    actions: MicrophoneSettingsActions,
    permission: HfpPermissionState,
    requestPermission: () -> Unit,
    update: (suspend () -> Unit) -> Unit,
) {
    val settings by repository.microphoneSettings.collectAsStateWithLifecycle<MicrophoneSettings?>(initialValue = null)
    val diagnostics by UtterlaneApp.instance.microphoneDiagnostics.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    // Reset only when the preset changes, not on every individual Custom edit.
    var customExpanded by rememberSaveable(settings?.preset) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }) {
        val current = settings
        if (current == null) {
            HelpText(stringResource(R.string.audio_input_loading))
        } else {
            MicrophoneSelector(stringResource(R.string.microphone_processing_title), current.preset,
                MicrophonePreset.entries, "microphone_preset", label = { presetLabel(it) }) { value ->
                update { actions.selectPreset(value) }
            }
            HelpText(stringResource(when (current.preset) {
                MicrophonePreset.DISABLED -> R.string.microphone_processing_disabled_summary
                MicrophonePreset.HFP_PRESET -> R.string.microphone_processing_hfp_summary
                MicrophonePreset.CUSTOM -> R.string.microphone_processing_custom_summary
            }))
            HelpText(stringResource(R.string.microphone_processing_next_recording))
            HelpText(stringResource(R.string.microphone_processing_gain_help))

            if (current.preset == MicrophonePreset.CUSTOM) {
                val expansion = stringResource(if (customExpanded) R.string.microphone_processing_expanded
                    else R.string.microphone_processing_collapsed)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.microphone_processing_custom_controls)) },
                    trailingContent = { Icon(if (customExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                    modifier = Modifier.testTag("microphone_custom_controls")
                        .semantics { stateDescription = expansion }
                        .clickable(role = Role.Button) { customExpanded = !customExpanded }
                )
                if (customExpanded) {
                    val options = current.options
                    MicrophoneSelector(stringResource(R.string.microphone_processing_source), options.source,
                        MicrophoneSource.entries, "microphone_source") { value ->
                        update { actions.updateOptions { it.copy(source = value) } }
                    }
                    MicrophoneSelector(stringResource(R.string.microphone_processing_route), options.route,
                        BluetoothCaptureRoute.entries, "microphone_route") { value ->
                        update { actions.selectRoute(value) }
                    }
                    MicrophoneSelector(stringResource(R.string.microphone_processing_mode), options.mode,
                        BluetoothAudioMode.entries, "microphone_mode") { value ->
                        update { actions.updateOptions { it.copy(mode = value) } }
                    }
                    MicrophoneSelector(stringResource(R.string.microphone_processing_preprocessing), options.preprocessing,
                        InputPreprocessingPolicy.entries, "microphone_preprocessing") { value ->
                        update { actions.updateOptions { it.copy(preprocessing = value) } }
                    }
                    HelpText(stringResource(R.string.microphone_processing_effects_help))
                    MicrophoneSelector(stringResource(R.string.microphone_processing_gain), options.gain,
                        PcmGainMode.entries, "microphone_gain", label = { gainLabel(it) }) { value ->
                        update { actions.updateOptions { it.copy(gain = value) } }
                    }
                }
            }
            // Keep the effective-mode caveat visible even with Custom collapsed.
            if (Build.VERSION.SDK_INT >= 31 && current.options.route == BluetoothCaptureRoute.STANDARD_SCO) {
                HelpText(stringResource(R.string.microphone_processing_standard_mode))
            }
            if (Build.VERSION.SDK_INT >= 31 && current.options.route == BluetoothCaptureRoute.HFP_VOICE_RECOGNITION) {
                HelpText(stringResource(when {
                    permission.granted -> R.string.microphone_hfp_permission_granted
                    permission.denied -> R.string.microphone_hfp_permission_denied
                    else -> R.string.microphone_hfp_permission_needed
                }))
                if (!permission.granted) {
                    // Always offer Settings: Android may suppress another prompt after
                    // denial, including after recreation when local denial state is gone.
                    TextButton(onClick = requestPermission, modifier = Modifier.padding(horizontal = 8.dp)
                        .testTag("microphone_hfp_grant")) {
                        Text(stringResource(R.string.microphone_hfp_permission_grant))
                    }
                    TextButton(onClick = { update {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.microphone_hfp_app_settings))
                    }
                }
            }
            TextButton(onClick = { update { actions.reset() } }, modifier = Modifier.padding(horizontal = 8.dp)
                .testTag("microphone_reset")) {
                Text(stringResource(R.string.microphone_processing_reset))
            }
        }

        // Diagnostics are outside the preset/expansion branches. Only this deliberate
        // copy action touches the clipboard; observation never exports technical data.
        val diagnosticsExpansion = stringResource(if (showDiagnostics) R.string.microphone_processing_expanded
            else R.string.microphone_processing_collapsed)
        ListItem(
            headlineContent = { Text(stringResource(R.string.microphone_processing_diagnostics)) },
            supportingContent = { Text(stringResource(if (diagnostics.active)
                R.string.microphone_processing_current_session else R.string.microphone_processing_last_session)) },
            trailingContent = { Icon(if (showDiagnostics) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
            modifier = Modifier.testTag("microphone_diagnostics")
                .semantics { stateDescription = diagnosticsExpansion }
                .clickable(role = Role.Button) { showDiagnostics = !showDiagnostics }
        )
        if (showDiagnostics) {
            val text = diagnostics.text()
            HelpText(stringResource(R.string.microphone_processing_diagnostics_help))
            SelectionContainer {
                Text(text, Modifier.padding(horizontal = 16.dp).testTag("microphone_diagnostics_text"),
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) },
                modifier = Modifier.padding(horizontal = 8.dp).testTag("microphone_diagnostics_copy")) {
                Text(stringResource(R.string.microphone_processing_copy))
            }
        }
    }
}

@Composable
private fun HelpText(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun presetLabel(value: MicrophonePreset) = stringResource(when (value) {
    MicrophonePreset.DISABLED -> R.string.microphone_processing_disabled
    MicrophonePreset.HFP_PRESET -> R.string.microphone_processing_hfp
    MicrophonePreset.CUSTOM -> R.string.microphone_processing_custom
})

@Composable
private fun gainLabel(value: PcmGainMode) = stringResource(when (value) {
    PcmGainMode.OFF -> R.string.microphone_processing_gain_off
    PcmGainMode.DB_PLUS_6 -> R.string.microphone_processing_gain_6
    PcmGainMode.DB_PLUS_12 -> R.string.microphone_processing_gain_12
    PcmGainMode.DB_PLUS_18 -> R.string.microphone_processing_gain_18
    PcmGainMode.AUTO_LEVEL -> R.string.microphone_processing_gain_auto
})

/** Full-row targets and radio semantics support touch, keyboard and screen-reader selection. */
@Composable
private fun <T : Enum<T>> MicrophoneSelector(
    title: String,
    selected: T,
    values: List<T>,
    tag: String,
    label: @Composable (T) -> String = { it.name },
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(label(selected)) },
        trailingContent = { Icon(Icons.Default.ExpandMore, null) },
        modifier = Modifier.testTag(tag).clickable(role = Role.Button) { open = true }
    )
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                values.forEach { value ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected == value, role = Role.RadioButton, onClick = {
                            open = false
                            onSelect(value)
                        }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == value, onClick = null)
                        Text(label(value), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
}
