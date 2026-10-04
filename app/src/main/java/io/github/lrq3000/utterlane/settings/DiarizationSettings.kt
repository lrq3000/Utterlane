package io.github.lrq3000.utterlane.settings

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.ModelManager
import kotlinx.coroutines.launch

@Composable
fun DiarizationSettings(onPickFolder: ((Uri) -> Unit) -> Unit) {
    val app = UtterlaneApp.instance
    val repository = app.settingsRepository
    val models = app.diarizationModels
    val enabled by repository.diarizationEnabled.collectAsStateWithLifecycle(false)
    val count by repository.speakerCount.collectAsStateWithLifecycle(0)
    val state by models.downloadState.collectAsStateWithLifecycle()
    var chooseCount by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) {
        app.applicationScope.launch {
            try { error = null; action() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
    }
    SettingsSection(stringResource(R.string.diarization_title)) {
        SwitchSettingItem(stringResource(R.string.diarization_enable), stringResource(R.string.diarization_description),
            Icons.Default.RecordVoiceOver, enabled, onCheckedChange = { run { repository.setDiarizationEnabled(it) } })
        ListItem(modifier = Modifier.clickable(enabled = enabled) { chooseCount = true }, headlineContent = { Text(stringResource(R.string.diarization_count)) },
            supportingContent = { Text(if (count == 0) stringResource(R.string.diarization_auto) else count.toString()) },
            trailingContent = { TextButton(enabled = enabled, onClick = { chooseCount = true }) { Text(stringResource(R.string.history_change)) } })
        Text(stringResource(R.string.diarization_count_help), Modifier.padding(horizontal = 16.dp))
        Text(stringResource(R.string.diarization_model_info), Modifier.padding(16.dp))
        when (val current = state) {
            is ModelManager.DownloadState.Downloading, is ModelManager.DownloadState.Copying -> {
                val progress = if (current is ModelManager.DownloadState.Downloading) current.progress else (current as ModelManager.DownloadState.Copying).progress
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                TextButton(onClick = { models.cancelTransfer() }) { Text(stringResource(R.string.action_cancel)) }
            }
            ModelManager.DownloadState.Ready -> {
                Text(stringResource(R.string.diarization_model_ready), Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { run { app.recognizerManager.deleteDiarizationModel() } }) { Text(stringResource(R.string.model_delete_button)) }
            }
            else -> {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    TextButton(onClick = { run { models.downloadModel() } }) { Text(stringResource(R.string.action_download)) }
                    TextButton(onClick = { onPickFolder { uri -> run { models.importFromFolder(uri) } } }) { Text(stringResource(R.string.action_load_local)) }
                }
                if (current is ModelManager.DownloadState.Error) Text(current.details ?: stringResource(R.string.model_error_checksum), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
    }
    if (chooseCount) AlertDialog(onDismissRequest = { chooseCount = false }, title = { Text(stringResource(R.string.diarization_count)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { (0..8).forEach { value ->
            TextButton(onClick = { run { repository.setSpeakerCount(value) }; chooseCount = false }) {
                Text((if (value == count) "✓ " else "") + if (value == 0) stringResource(R.string.diarization_auto) else value.toString())
            }
        } } }, confirmButton = { TextButton(onClick = { chooseCount = false }) { Text(stringResource(R.string.action_cancel)) } })
}
