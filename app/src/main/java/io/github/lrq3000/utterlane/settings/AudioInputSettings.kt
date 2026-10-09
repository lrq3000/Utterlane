package io.github.lrq3000.utterlane.settings

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.audio.AudioInputController
import io.github.lrq3000.utterlane.ui.AudioInputText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Settings observe the next input; active recording owns its independent route. */
@Composable
fun AudioInputSettings(
    controller: AudioInputController = UtterlaneApp.instance.audioInputs,
    repository: SettingsRepository = UtterlaneApp.instance.settingsRepository,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var choose by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = remember(context) { HfpPermissionState(context) }
    fun update(action: suspend () -> Unit) {
        scope.launch {
            try { error = null; action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("AudioInputSettings", "Could not update audio input", e)
                error = context.getString(R.string.microphone_processing_update_failed)
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission.onResult(granted)
        update { controller.refresh() }
    }
    val requestPermission = {
        // A settings write can finish after the user backgrounds the activity.
        // Keep the explicit grant action available instead of opening UI there.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            permission.requestIfNeeded { launcher.launch(it) }
        }
    }
    val actions = remember(repository, controller, permission, launcher) {
        MicrophoneSettingsActions(repository, controller, requestPermission)
    }
    DisposableEffect(lifecycle, controller, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permission.refresh()
                update { controller.refresh() }
            }
        }
        lifecycle.addObserver(observer)
        permission.refresh()
        update { controller.refresh() }
        onDispose { lifecycle.removeObserver(observer) }
    }
    ListItem(
        modifier = Modifier.clickable(role = Role.Button) { choose = true; update { controller.refresh() } },
        headlineContent = { Text(stringResource(R.string.audio_input_title)) },
        supportingContent = { Text(state?.selected?.let { AudioInputText.name(context, it) }
            ?: stringResource(R.string.audio_input_loading)) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) }
    )
    SwitchSettingItem(
        title = stringResource(R.string.audio_input_prefer_bluetooth),
        subtitle = stringResource(R.string.audio_input_prefer_description),
        icon = Icons.Default.BluetoothAudio,
        checked = state?.preferences?.preferBluetooth == true,
        enabled = state != null,
        onCheckedChange = { enabled -> update { actions.setPreferBluetooth(enabled) } }
    )
    Text(stringResource(R.string.audio_input_description), Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
    MicrophoneProcessingSettings(repository, actions, permission, requestPermission, ::update)

    if (choose) AlertDialog(
        onDismissRequest = { choose = false },
        title = { Text(stringResource(R.string.audio_input_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                val options = state?.inputs
                if (options == null) Text(error ?: stringResource(R.string.audio_input_loading))
                options?.forEach { input ->
                    val selected = input.key == state?.preferences?.selectedKey
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected,
                        role = Role.RadioButton, onClick = {
                            update {
                                // Selection is revalidated inside the controller's
                                // transaction, even if this open dialog became stale.
                                if (actions.selectInput(input)) choose = false
                                else error = context.getString(R.string.audio_input_selection_gone)
                            }
                        }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = null)
                        Text(AudioInputText.name(context, input), Modifier.padding(start = 12.dp))
                    }
                }
                if (options != null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { choose = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
}
