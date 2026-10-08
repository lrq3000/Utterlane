package io.github.lrq3000.utterlane.transcribe

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.RecordingRecovery

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptionDialog(model: TranscriptionDialogModel, onClose: () -> Unit, onDelete: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val app = UtterlaneApp.instance
    val context = LocalContext.current
    val view = LocalView.current
    val working = state.running || state.importing || state.saving
    DisposableEffect(view, working) {
        val previous = view.keepScreenOn
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = previous }
    }
    val stats by app.settingsRepository.showTranscriptionStreamStatistics.collectAsStateWithLifecycle(initialValue = false)
    var audioMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var beforeModel by remember { mutableStateOf("") }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (app.modelManager.selected.value.id != beforeModel) model.retry(useCurrentModel = true)
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(state.audio?.mimeType ?: "audio/*")) { uri ->
        uri?.let { model.exportAudio(it, directory = false) }
    }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { model.exportAudio(it, directory = true) }
    }
    Surface(color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Card(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).padding(vertical = 24.dp),
                shape = MaterialTheme.shapes.extraLarge,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.transcribe_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose, enabled = !state.closing) { Icon(Icons.Default.Close, stringResource(R.string.transcribe_close)) }
                    }
                    if (state.running || state.importing || state.saving || state.closing) {
                        if (state.progress == null || !state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { state.progress!! / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(when {
                            state.closing -> R.string.dialog_closing
                            state.saving -> R.string.dialog_saving
                            state.importing -> R.string.dialog_importing
                            else -> R.string.transcribe_transcribing
                        }))
                    }
                    if (stats) {
                        Text(io.github.lrq3000.utterlane.ui.RecognitionStatusText.activity(context, state.capture.recognition), style = MaterialTheme.typography.bodySmall)
                        Text(io.github.lrq3000.utterlane.ui.RecognitionStatusText.backlog(context, state.capture), style = MaterialTheme.typography.bodySmall)
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!state.importing) Text(stringResource(when {
                        state.audio == null -> R.string.dialog_audio_unavailable
                        state.audio!!.pinned -> R.string.history_pinned
                        state.audio!!.temporary -> R.string.dialog_temporary_info
                        else -> R.string.dialog_retained_info
                    }), style = MaterialTheme.typography.bodySmall)

                    AudioPlaybackControls(model)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            Button(onClick = { modelMenu = true }, enabled = state.audio != null && !state.running && !state.importing && !state.closing) {
                                Text(stringResource(R.string.dialog_retranscribe))
                            }
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_same_model)) }, onClick = { modelMenu = false; model.retry() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.recording_choose_model)) }, onClick = {
                                    modelMenu = false; beforeModel = app.modelManager.selected.value.id
                                    chooser.launch(RecordingRecovery.modelIntent(context))
                                })
                            }
                        }
                        Box {
                            FilledTonalButton(onClick = { audioMenu = true }, enabled = state.audio != null && !state.saving && !state.closing) {
                                Text(stringResource(R.string.dialog_save_audio))
                            }
                            DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(if (state.audio?.pinned == true) R.string.history_unpin else R.string.dialog_save_history)) }, onClick = {
                                    audioMenu = false
                                    model.setAudioPinned(state.audio?.pinned != true)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_share_audio)) }, onClick = {
                                    audioMenu = false; model.shareAudio { context.startActivity(Intent.createChooser(it, context.getString(R.string.dialog_share_audio))) }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_save_device)) }, onClick = {
                                    audioMenu = false
                                    state.audio?.let { if (it.parts > 1) directory.launch(null) else file.launch(it.part(0).name) }
                                })
                            }
                        }
                    }
                    if (state.preview.isNotEmpty()) {
                        TranscriptText(state.preview, Modifier.padding(vertical = 12.dp))
                        TranscriptTransferActions(state.store) {
                            TextButton(onClick = { model.setTranscriptPinned(!state.transcriptPinned) }, enabled = !state.running && !state.saving && !state.closing) {
                                Text(stringResource(if (state.transcriptPinned) R.string.history_unpin else R.string.dialog_keep_text))
                            }
                        }
                        Row {
                            TextButton(onClick = { model.page(true) }) { Text(stringResource(R.string.stream_previous)) }
                            TextButton(onClick = { model.page(false) }) { Text(stringResource(R.string.stream_next)) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDelete, enabled = !state.closing) {
                            Text(stringResource(if (model.input.transcriptOrigin && state.transcriptId != null) R.string.dialog_delete_text
                                else if (!model.input.transcriptOrigin && state.audio?.temporary == false) R.string.history_delete else R.string.dialog_discard))
                        }
                        TextButton(onClick = onClose, enabled = !state.closing) { Text(stringResource(R.string.transcribe_close)) }
                    }
                }
            }
        }
    }
}
