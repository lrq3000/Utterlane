package io.github.lrq3000.utterlane.transcribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.RecordingRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** D: infrequent audio actions above the document, common text actions by the
 * user's thumbs. The reader alone consumes the remaining height and scrolls. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TranscriptionDialog(model: TranscriptionDialogModel, onClose: () -> Unit, onEmpty: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val app = UtterlaneApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val busy = state.importing || state.saving || state.closing || state.deleting
    val working = busy || state.running || state.checkingDeletion
    DisposableEffect(view, working) {
        val previous = view.keepScreenOn
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(state.finished) { if (state.finished) onEmpty() }
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
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        Box(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Card(Modifier.widthIn(max = 560.dp).fillMaxSize().testTag("transcription_dialog"),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        DialogAction(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.dialog_back),
                            "dialog_back", !state.closing && !state.deleting, tint = MaterialTheme.colorScheme.onSurface, onClick = onClose)
                        AutoFitDialogTitle(stringResource(R.string.dialog_title), Modifier.weight(1f).padding(end = 4.dp))
                        Box {
                            DialogAction(Icons.Outlined.Refresh, stringResource(R.string.dialog_retranscribe), "dialog_retranscribe",
                                state.audio != null && !state.running && !busy, onClick = { modelMenu = true })
                            DropdownMenu(modelMenu, { modelMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_same_model)) }, onClick = { modelMenu = false; model.retry() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.recording_choose_model)) }, onClick = {
                                    modelMenu = false; beforeModel = app.modelManager.selected.value.id
                                    chooser.launch(RecordingRecovery.modelIntent(context))
                                })
                            }
                        }
                        Box {
                            DialogAction(Icons.Outlined.FileDownload, stringResource(R.string.dialog_save_audio), "dialog_audio_menu",
                                state.audio != null && !busy, onClick = { audioMenu = true })
                            DropdownMenu(audioMenu, { audioMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_save_history)) }, onClick = { audioMenu = false; model.saveAudioToHistory() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_share_audio)) }, onClick = {
                                    audioMenu = false; model.shareAudio { context.startActivity(Intent.createChooser(it, context.getString(R.string.dialog_share_audio))) }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_save_device)) }, onClick = {
                                    audioMenu = false
                                    state.audio?.let { if (it.parts > 1) directory.launch(null) else file.launch(it.part(0).name) }
                                })
                            }
                        }
                        DialogDeletionControl(model, state, busy)
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        if (!state.importing) Text(stringResource(when {
                            state.audio == null -> R.string.dialog_audio_unavailable
                            state.audio!!.pinned -> R.string.dialog_audio_pinned
                            state.audio!!.temporary -> R.string.dialog_temporary_info
                            else -> R.string.dialog_audio_saved
                        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    TranscriptReader(model, state, Modifier.weight(1f).fillMaxWidth()) {
                        TranscriptionProgressFooter(state, stats)
                    }
                    AudioPlaybackControls(model) {
                        val hasText = state.transcriptBytes > 0
                        DialogPinControl(model, state, busy)
                        DialogAction(Icons.Outlined.ContentCopy, stringResource(R.string.transcribe_copy), "dialog_copy", hasText && !state.deleting,
                            onClick = { scope.launch {
                                val text = withContext(Dispatchers.IO) { state.store?.readForTransfer() }
                                if (text != null) (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Transcript", text))
                                else android.widget.Toast.makeText(context, R.string.stream_use_export, android.widget.Toast.LENGTH_LONG).show()
                            } })
                        DialogAction(Icons.Outlined.Share, stringResource(R.string.dialog_share_text), "dialog_share", hasText && !state.deleting,
                            onClick = { state.store?.let { shareTranscript(context, it) } })
                    }
                }
            }
        }
    }
}
