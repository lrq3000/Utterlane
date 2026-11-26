package io.github.lrq3000.utterlane.transcribe

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag as accessibilityTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.SpeakerWarning
import io.github.lrq3000.utterlane.history.RecordingRecovery
import io.github.lrq3000.utterlane.settings.SpeakerModelNotice
import io.github.lrq3000.utterlane.settings.SettingsRepository
import kotlinx.coroutines.launch

/** D: infrequent audio actions above the document, common text actions by the
 * user's thumbs. The reader alone consumes the remaining height and scrolls. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TranscriptionDialog(model: TranscriptionDialogModel, onClose: () -> Unit, onEmpty: () -> Unit,
    onBackground: (() -> Unit)? = null, jobControls: QueueJobControls? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val app = UtterlaneApp.instance
    val context = LocalContext.current
    val openRetry: (String) -> Unit = { id -> context.startActivity(QueueNotifications.intent(context, id)) }
    val view = LocalView.current
    val busy = state.importing || state.saving || state.closing || state.deleting || state.checkingExit
    val working = busy || state.running || state.checkingDeletion
    DisposableEffect(view, working) {
        val previous = view.keepScreenOn
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(state.finished, state.exited, state.importCancelled) {
        if (state.finished || state.exited || state.importCancelled) onEmpty()
    }
    val stats by app.settingsRepository.showTranscriptionStreamStatistics.collectAsStateWithLifecycle(initialValue = false)
    var audioMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var beforeModel by remember { mutableStateOf("") }
    val queueId = model.input.queueId
    var confirmQueueDeletion by rememberSaveable(queueId) { mutableStateOf(false) }
    // Survives process recreation too. A restored picker result must be consumed
    // (and rejected by a new model with no lease) before another picker is begun.
    var pickerPending by rememberSaveable { mutableStateOf(false) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (app.modelManager.selected.value.id != beforeModel) model.enqueueRetry(useCurrentModel = true, onQueued = openRetry)
    }
    val file = rememberLauncherForActivityResult(AudioExportContract()) { uri ->
        pickerPending = false
        model.finishAudioExport(uri, directory = false)
    }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pickerPending = false
        model.finishAudioExport(uri, directory = true)
    }
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            val compactProcessing = jobControls != null && maxHeight < 280.dp
            val compactProgressHeight = maxHeight / 2
            Card(Modifier.widthIn(max = 560.dp).fillMaxSize().testTag("transcription_dialog"),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        DialogAction(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.dialog_back),
                            "dialog_back", !state.closing && !state.deleting, tint = MaterialTheme.colorScheme.onSurface, onClick = onClose)
                        ReaderSizeTitle(Modifier.weight(1f).padding(end = 4.dp))
                        if (jobControls == null || jobControls.stage == QueueStage.READY || jobControls.stage == QueueStage.FAILED) Box {
                            DialogAction(Icons.Outlined.Refresh, stringResource(R.string.dialog_retranscribe), "dialog_retranscribe",
                                state.audio != null && !state.running && !busy, onClick = { modelMenu = true })
                            DropdownMenu(modelMenu, { modelMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_same_model)) }, onClick = { modelMenu = false; model.enqueueRetry(onQueued = openRetry) })
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
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_share_audio)) }, enabled = !state.audioExporting && !pickerPending, onClick = {
                                    audioMenu = false; model.shareAudio { context.startActivity(Intent.createChooser(it, context.getString(R.string.dialog_share_audio))) }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_save_device)) }, enabled = !state.audioExporting && !pickerPending, onClick = {
                                    audioMenu = false
                                    model.prepareAudioExport { request ->
                                        pickerPending = true
                                        try { if (request.directory) directory.launch(null) else file.launch(request) }
                                        catch (error: Exception) { pickerPending = false; throw error }
                                    }
                                })
                            }
                        }
                        when {
                            jobControls != null -> QueueDeleteAction(jobControls.delete, enabled = !state.closing && !state.deleting)
                            queueId != null -> QueueDeleteAction({ confirmQueueDeletion = true }, enabled = !state.closing && !state.deleting)
                            else -> DialogDeletionControl(model, state, busy)
                        }
                    }
                    // The weighted reader can collapse after the playback toolbar wraps. In a short window measure the scrollable processing dock first so Stop and Continue remain reachable.
                    if (compactProcessing) TranscriptionProgressFooter(state, stats, jobControls, Modifier.heightIn(max = compactProgressHeight))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        if (!state.importing) Text(stringResource(when {
                            state.audio == null -> R.string.dialog_audio_unavailable
                            state.audio!!.pinned -> R.string.dialog_audio_pinned
                            state.audio!!.temporary -> R.string.dialog_temporary_info
                            else -> R.string.dialog_audio_saved
                        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (state.speakerWarning) {
                            SpeakerWarning.MODEL_NEEDED -> SpeakerModelNotice()
                            SpeakerWarning.PROCESSING_FAILED -> Text(
                                stringResource(R.string.speaker_file_processing_failed), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            null -> Unit
                        }
                        if (state.partial) Text(stringResource(R.string.transcript_partial),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("transcript_partial"))
                    }
                    Spacer(Modifier.height(8.dp))
                    TranscriptReader(model.document, state, Modifier.weight(1f).fillMaxWidth()) {
                        if (!compactProcessing) TranscriptionProgressFooter(state, stats, jobControls)
                    }
                    AudioPlaybackControls(model) {
                        val hasText = state.transcriptBytes > 0
                        if (onBackground != null) QueueBackgroundAction(onBackground)
                        DialogPinControl(model, state, busy)
                        TranscriptTransferActions(state.store, enabled = hasText && !state.deleting, iconsOnly = true)
                        io.github.lrq3000.utterlane.archive.ManagedMarkdownAction(model, hasText && !working && !model.input.unfinishedQueueView)
                    }
                    io.github.lrq3000.utterlane.archive.ArchivalStatusControls()
                }
            }
        }
    }
    DialogExitConfirmation(model, state)
    ImportRetentionQuestion(model)
    // Home may borrow a queue-owned review model without mounting the queue Activity's confirmation.
    if (queueId != null && jobControls == null && confirmQueueDeletion) {
        QueueCancelDialog(queueId) { confirmQueueDeletion = false }
    }
}

@Composable
internal fun QueueBackgroundAction(onBackground: () -> Unit) {
    DialogAction(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.queue_background), "dialog_background", onClick = onBackground)
}

@Composable
internal fun QueueDeleteAction(onDelete: () -> Unit, enabled: Boolean = true) {
    DialogAction(Icons.Outlined.Delete, stringResource(R.string.reader_delete_job), "dialog_delete", enabled,
        tint = MaterialTheme.colorScheme.error, onClick = onDelete)
}

/** Use the title's existing width rather than fitting a fifth full-size icon in the compact header. Only the transcript pages consume this preference. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ReaderSizeTitle(modifier: Modifier) {
    val app = UtterlaneApp.instance
    val size by app.settingsRepository.transcriptTextSize.collectAsStateWithLifecycle(SettingsRepository.DEFAULT_TRANSCRIPT_TEXT_SIZE)
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.reader_font_size)
    val current = stringResource(R.string.reader_size_sp, size)
    val show: () -> Unit = { open = true }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(48.dp).clearAndSetSemantics {
            // Expose a single named click target, including on older Android accessibility delegates that otherwise split off the description.
            accessibilityTag = "dialog_title"
            role = Role.Button
            contentDescription = label
            stateDescription = current
            onClick(label) { show(); true }
        }.clickable(onClick = show),
            verticalAlignment = Alignment.CenterVertically) {
            AutoFitDialogTitle(stringResource(R.string.dialog_title), Modifier.weight(1f))
            Icon(Icons.Outlined.FormatSize, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            SettingsRepository.TRANSCRIPT_TEXT_SIZES.forEach { option ->
                val name = stringResource(if (option == SettingsRepository.DEFAULT_TRANSCRIPT_TEXT_SIZE) R.string.reader_size_default else R.string.reader_size_sp, option)
                val chosen = option == size
                val choose: () -> Unit = { open = false; app.applicationScope.launch { app.settingsRepository.setTranscriptTextSize(option) }; Unit }
                DropdownMenuItem(text = { Text(name) }, trailingIcon = { if (chosen) Icon(Icons.Outlined.Check, null) },
                    modifier = Modifier.clearAndSetSemantics {
                        accessibilityTag = "reader_text_size_$option"
                        role = Role.RadioButton
                        contentDescription = name
                        selected = chosen
                        onClick { choose(); true }
                    }, onClick = choose)
            }
        }
    }
}
