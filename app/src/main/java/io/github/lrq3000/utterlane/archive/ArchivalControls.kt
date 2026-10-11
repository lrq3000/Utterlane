package io.github.lrq3000.utterlane.archive

import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.SettingsSection
import io.github.lrq3000.utterlane.settings.SwitchSettingItem
import io.github.lrq3000.utterlane.transcribe.DialogAction
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SimpleStorage 3.1 contract, shared by settings and the result action. Grants
 * are retained when changing folders because existing jobs/files still use them. */
@Composable
private fun folderPicker(chosen: suspend (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val app = UtterlaneApp.instance
    val callback by rememberUpdatedState(chosen)
    val contract = remember(context) { ArchivalFolderContract(context) }
    val picker = rememberLauncherForActivityResult(contract) { result ->
        if (result != null) app.applicationScope.launch {
            try {
                val uri = result
                require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
                val tree = uri // Exact picker/grant identity, including opaque encoded IDs.
                withContext(Dispatchers.IO) {
                    context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    check(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
                    callback(tree.toString())
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.archive_grant_failed) + " " + e.message.orEmpty(), Toast.LENGTH_LONG).show()
            }
        }
    }
    fun launchPicker() {
        try { picker.launch(Unit) }
        catch (e: Exception) { Toast.makeText(context, e.message ?: context.getString(R.string.archive_grant_failed), Toast.LENGTH_LONG).show() }
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) launchPicker()
        else Toast.makeText(context, R.string.archive_grant_failed, Toast.LENGTH_LONG).show()
    }
    return {
        // SimpleStorage's API 26-28 picker contract requires the legacy runtime
        // grant. The merged permission is capped at 28; later SDKs use SAF only.
        if (android.os.Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            legacy.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else launchPicker()
    }
}

@Composable
fun ArchivalSettingsSection() {
    val app = UtterlaneApp.instance
    val settings by app.settingsRepository.archival.collectAsStateWithLifecycle(ArchivalSettings())
    val audio = folderPicker { app.settingsRepository.setAudioFolder(it) }
    val text = folderPicker { app.settingsRepository.setMarkdownFolder(it) }
    SettingsSection(title = stringResource(R.string.archive_title)) {
        ListItem(headlineContent = { Text(stringResource(R.string.archive_audio_folder)) },
            supportingContent = { Text(settings.audioFolder ?: stringResource(R.string.archive_private)) },
            leadingContent = { Icon(Icons.Outlined.Folder, null) }, trailingContent = {
                TextButton(onClick = audio, modifier = Modifier.testTag("archive_audio_folder")) { Text(stringResource(R.string.archive_choose_folder)) }
            })
        if (settings.audioFolder != null) TextButton(onClick = {
            app.applicationScope.launch { app.settingsRepository.setAudioFolder(null) }
        }) { Text(stringResource(R.string.archive_private)) }
        Text(stringResource(R.string.archive_audio_explanation), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        ListItem(headlineContent = { Text(stringResource(R.string.archive_markdown_folder)) },
            supportingContent = { Text(settings.markdownFolder ?: stringResource(R.string.archive_no_folder)) },
            trailingContent = { TextButton(onClick = text, modifier = Modifier.testTag("archive_markdown_folder")) { Text(stringResource(R.string.archive_choose_folder)) } })
        SwitchSettingItem(title = stringResource(R.string.archive_auto_markdown), subtitle = stringResource(R.string.archive_markdown_explanation),
            icon = Icons.Outlined.FileDownload, checked = settings.automaticMarkdown, onCheckedChange = { enabled ->
                app.applicationScope.launch { app.settingsRepository.setAutomaticMarkdown(enabled) }
                if (enabled && settings.markdownFolder == null) text()
            })
        ArchivalStatusControls(alwaysShow = true)
    }
}

@Composable
fun ArchivalStatusControls(alwaysShow: Boolean = false) {
    val coordinator = UtterlaneApp.instance.archival
    val status by coordinator.state.collectAsStateWithLifecycle()
    if (alwaysShow || status.hasWork || status.message != null) Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(stringResource(R.string.archive_status, status.queued, status.running, status.paused, status.cleanup), style = MaterialTheme.typography.bodySmall)
        status.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (status.hasWork) {
            TextButton(onClick = coordinator::forceStopAll, modifier = Modifier.testTag("archive_force_stop")) { Text(stringResource(R.string.archive_force_stop)) }
            if (status.paused > 0 || status.cleanup > 0 || status.queued > 0)
                TextButton(onClick = coordinator::retry, modifier = Modifier.testTag("archive_retry")) { Text(stringResource(R.string.archive_retry)) }
        }
    }
}

@Composable
fun ManagedMarkdownAction(model: TranscriptionDialogModel?, enabled: Boolean) {
    val app = UtterlaneApp.instance
    val settings by app.settingsRepository.archival.collectAsStateWithLifecycle(ArchivalSettings())
    var selectedModel by remember { mutableStateOf<TranscriptionDialogModel?>(null) }
    val pick = folderPicker { uri ->
        app.settingsRepository.setMarkdownFolder(uri)
        withContext(Dispatchers.Main) { selectedModel?.saveMarkdown(uri) }
    }
    DialogAction(Icons.Outlined.FileDownload, stringResource(R.string.archive_save_markdown), "save_managed_markdown", enabled && model != null) {
        if (settings.markdownFolder == null) { selectedModel = model; pick() } else model?.saveMarkdown()
    }
}
