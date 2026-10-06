package io.github.lrq3000.utterlane.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Editors keep a whole group as a draft: FIFO/update and window/context can change together. */
@Composable
fun AdvancedRecognitionSettings(repository: SettingsRepository = UtterlaneApp.instance.settingsRepository) {
    val options by repository.runtimeOptions.collectAsStateWithLifecycle<RuntimeOptions?>(null)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RuntimeOptionGroup?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(action: suspend () -> Unit) {
        saving = true
        error = null
        scope.launch {
            try { action(); editing = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            finally { saving = false }
        }
    }
    SettingsSection(stringResource(R.string.advanced_title)) {
        ListItem(
            modifier = Modifier.clickable { expanded = !expanded },
            headlineContent = { Text(stringResource(R.string.advanced_show_options)) },
            supportingContent = { Text(stringResource(R.string.advanced_summary)) },
            trailingContent = { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                stringResource(if (expanded) R.string.advanced_collapse else R.string.advanced_expand)) }
        )
        val current = options
        if (expanded && current != null) {
            Text(stringResource(R.string.advanced_apply_help), Modifier.padding(16.dp))
            Text(stringResource(R.string.advanced_effective,
                RuntimeOptions.resolveThreads(current.asrThreads), RuntimeOptions.resolveThreads(current.diarizationThreads),
                current.asrWindowSeconds.toString(), current.queueSeconds), Modifier.padding(horizontal = 16.dp))
            Text(stringResource(R.string.advanced_fixed_capabilities), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            val values = current.toMap()
            RuntimeOptionGroup.entries.forEach { group ->
                val fields = RuntimeOptions.fields.filter { it.group == group }
                val summary = fields.map { field ->
                    stringResource(R.string.advanced_named_value, stringResource(fieldLabel(field.key)), displayValue(field, values.getValue(field.key)))
                }.joinToString("\n")
                ListItem(
                    modifier = Modifier.clickable(enabled = !saving) { error = null; editing = group },
                    headlineContent = { Text(stringResource(groupTitle(group))) },
                    supportingContent = { Text(summary) },
                    trailingContent = { TextButton(enabled = !saving, onClick = { error = null; editing = group }) {
                        Text(stringResource(R.string.advanced_edit))
                    } }
                )
            }
            TextButton(enabled = !saving, onClick = { save { repository.resetRuntimeOptions() } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.advanced_reset_all))
            }
            if (editing == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            LocalDiagnosticsPanel(enabled = current.diagnostics)
        }
    }
    val group = editing
    val current = options
    if (group != null && current != null) {
        AdvancedGroupEditor(group, current, saving, error, onDismiss = { if (!saving) editing = null }) { draft ->
            save { repository.updateRuntimeGroup(group, draft) }
        }
    }
}

@Composable
private fun AdvancedGroupEditor(
    group: RuntimeOptionGroup, current: RuntimeOptions, saving: Boolean, saveError: String?,
    onDismiss: () -> Unit, onApply: (Map<String, String>) -> Unit
) {
    val fields = remember(group) { RuntimeOptions.fields.filter { it.group == group } }
    // Deliberately not keyed by current: incoming DataStore emissions must not erase typing.
    val initial = remember(group) { current.toMap() }
    val defaults = remember { RuntimeOptions().toMap() }
    var draft by remember(group) { mutableStateOf(fields.associate { it.key to initial.getValue(it.key) }) }
    val validation = remember(draft) { RuntimeOptions.parseDraft(initial + draft) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(groupTitle(group))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(groupHelp(group)))
                if (group == RuntimeOptionGroup.DIARIZATION) Text(stringResource(R.string.advanced_attribution_policy_help))
                Text(stringResource(R.string.advanced_draft_help), style = MaterialTheme.typography.bodySmall)
                fields.forEach { field ->
                    AdvancedOptionEditor(field, draft.getValue(field.key), initial.getValue(field.key), defaults.getValue(field.key),
                        validation.errors[field.key], enabled = !saving) { draft = draft + (field.key to it) }
                }
                TextButton(enabled = !saving, onClick = { draft = fields.associate { it.key to defaults.getValue(it.key) } }) {
                    Text(stringResource(R.string.advanced_reset_group))
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && validation.options != null, onClick = { onApply(draft) }) {
                Text(stringResource(R.string.advanced_apply))
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun AdvancedOptionEditor(
    field: RuntimeOptionField, value: String, current: String, default: String, error: String?,
    enabled: Boolean, onChange: (String) -> Unit
) {
    val label = stringResource(fieldLabel(field.key))
    val help = stringResource(R.string.advanced_current_default, displayValue(field, current), displayValue(field, default))
    when (field.kind) {
        RuntimeOptionKind.BOOLEAN -> ListItem(
            headlineContent = { Text(label) }, supportingContent = { Text(help) },
            trailingContent = { Switch(value == "true", { onChange(it.toString()) }, enabled = enabled) }
        )
        RuntimeOptionKind.CHOICE -> Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(help, style = MaterialTheme.typography.bodySmall)
            field.choices.forEach { choice ->
                Row(Modifier.fillMaxWidth().selectable(value == choice, enabled = enabled, role = Role.RadioButton,
                    onClick = { onChange(choice) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(value == choice, onClick = null, enabled = enabled)
                    Text(displayValue(field, choice), Modifier.padding(start = 8.dp))
                }
            }
        }
        else -> OutlinedTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text(label) }, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = if (field.kind == RuntimeOptionKind.INTEGER) KeyboardType.Number else KeyboardType.Decimal),
            supportingText = { Text(if (error == null) help else "$help\n${validationMessage(field, value)}") }
        )
    }
}

/** Core validation stays Android-free; user-facing explanations come from locale resources. */
@Composable
private fun validationMessage(field: RuntimeOptionField, value: String): String {
    if (field.error(value.trim()) != null) {
        return if (field.kind == RuntimeOptionKind.INTEGER) {
            stringResource(R.string.advanced_invalid_integer, field.minimum.toLong().toString(), field.maximum.toLong().toString())
        } else {
            stringResource(R.string.advanced_invalid_decimal, field.minimum.toString(), field.maximum.toString())
        }
    }
    return stringResource(if (field.group == RuntimeOptionGroup.AUDIO) R.string.advanced_invalid_windows else R.string.advanced_invalid_cache)
}

@Composable
private fun displayValue(field: RuntimeOptionField, value: String): String = when {
    field.kind == RuntimeOptionKind.BOOLEAN -> stringResource(if (value == "true") R.string.advanced_on else R.string.advanced_off)
    field.kind == RuntimeOptionKind.CHOICE -> stringResource(when (value) {
        "low_latency" -> R.string.advanced_low_latency
        "ultra_low_latency" -> R.string.advanced_ultra_low_latency
        else -> R.string.advanced_very_low_latency
    })
    field.key.endsWith("_threads") && value == "0" -> stringResource(R.string.advanced_auto)
    field.key.endsWith("_seconds") -> stringResource(R.string.advanced_seconds, value)
    field.key.endsWith("_ms") -> stringResource(R.string.advanced_milliseconds, value)
    field.key.endsWith("_frames") -> stringResource(R.string.advanced_frames, value)
    else -> value
}

private fun groupTitle(group: RuntimeOptionGroup): Int = when (group) {
    RuntimeOptionGroup.RECOVERY -> R.string.advanced_recovery
    RuntimeOptionGroup.CPU -> R.string.advanced_cpu
    RuntimeOptionGroup.DIARIZATION -> R.string.advanced_diarization
    RuntimeOptionGroup.AUDIO -> R.string.advanced_audio
    RuntimeOptionGroup.CAPTURE -> R.string.advanced_capture
    RuntimeOptionGroup.DOWNLOADS -> R.string.advanced_downloads
    RuntimeOptionGroup.EXPERIMENTAL -> R.string.advanced_experimental
}

private fun groupHelp(group: RuntimeOptionGroup): Int = when (group) {
    RuntimeOptionGroup.RECOVERY -> R.string.advanced_recovery_help
    RuntimeOptionGroup.CPU -> R.string.advanced_cpu_help
    RuntimeOptionGroup.DIARIZATION -> R.string.advanced_diarization_help
    RuntimeOptionGroup.AUDIO -> R.string.advanced_audio_help
    RuntimeOptionGroup.CAPTURE -> R.string.advanced_capture_help
    RuntimeOptionGroup.DOWNLOADS -> R.string.advanced_downloads_help
    RuntimeOptionGroup.EXPERIMENTAL -> R.string.advanced_experimental_help
}

private fun fieldLabel(key: String): Int = fieldLabels.getValue(key)
private val fieldLabels = mapOf(
    "worker_connect_seconds" to R.string.advanced_worker_connect,
    "prepare_stall_seconds" to R.string.advanced_prepare_stall,
    "inference_stall_seconds" to R.string.advanced_inference_stall,
    "absolute_operation_seconds" to R.string.advanced_absolute_operation,
    "asr_threads" to R.string.advanced_asr_threads,
    "diarization_threads" to R.string.advanced_diarization_threads,
    "diarization_mode" to R.string.advanced_diarization_mode,
    "diarization_batch" to R.string.advanced_diarization_batch,
    "speaker_threshold" to R.string.advanced_speaker_threshold,
    "speaker_margin" to R.string.advanced_speaker_margin,
    "speaker_confirmation_ms" to R.string.advanced_speaker_confirmation,
    "unknown_bridge_ms" to R.string.advanced_unknown_bridge,
    "label_lookahead_ms" to R.string.advanced_label_lookahead,
    "alignment_tolerance_ms" to R.string.advanced_alignment_tolerance,
    "asr_window_seconds" to R.string.advanced_asr_window,
    "asr_min_seconds" to R.string.advanced_asr_min,
    "asr_left_context_seconds" to R.string.advanced_asr_left_context,
    "asr_right_context_seconds" to R.string.advanced_asr_right_context,
    "silence_duration_ms" to R.string.advanced_silence_duration,
    "silence_amplitude" to R.string.advanced_silence_amplitude,
    "queue_seconds" to R.string.advanced_queue,
    "capture_buffer_seconds" to R.string.advanced_capture_buffer,
    "capture_block_ms" to R.string.advanced_capture_block,
    "wake_recovery_ms" to R.string.advanced_wake_recovery,
    "wake_reopen_ms" to R.string.advanced_wake_reopen,
    "download_connect_seconds" to R.string.advanced_download_connect,
    "download_read_seconds" to R.string.advanced_download_read,
    "native_cache_frames" to R.string.advanced_native_cache,
    "native_fifo_frames" to R.string.advanced_native_fifo,
    "native_update_frames" to R.string.advanced_native_update,
    "diagnostics" to R.string.advanced_diagnostics,
    "strong_speaker_threshold" to R.string.advanced_strong_speaker_threshold,
    "strong_speaker_margin" to R.string.advanced_strong_speaker_margin,
    "strong_confirmation_ms" to R.string.advanced_strong_confirmation,
    "word_fallback_ms" to R.string.advanced_word_fallback
)
