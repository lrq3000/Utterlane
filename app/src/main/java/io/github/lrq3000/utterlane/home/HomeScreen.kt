package io.github.lrq3000.utterlane.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.asr.CaptureSignal
import io.github.lrq3000.utterlane.history.RecordingRecovery
import io.github.lrq3000.utterlane.transcribe.TranscriptReader
import io.github.lrq3000.utterlane.transcribe.TranscriptTransferActions
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialog
import io.github.lrq3000.utterlane.ui.RecognitionStatusText
import io.github.lrq3000.utterlane.ui.WaveformButton
import io.github.lrq3000.utterlane.ui.theme.LocalBrandPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun HomeScreen(controller: HomeController, onRecord: () -> Unit, onLoad: () -> Unit, onSettings: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val app = UtterlaneApp.instance
    val context = LocalContext.current
    val speakers by app.settingsRepository.diarizationEnabled.collectAsStateWithLifecycle(false)
    val speakerCount by app.settingsRepository.speakerCount.collectAsStateWithLifecycle(0)
    val speakerDownload by app.diarizationModels.downloadState.collectAsStateWithLifecycle()
    val speakerReady = remember(speakerDownload) { app.diarizationModels.isModelReady() }
    val modelDownload by app.modelManager.downloadState.collectAsStateWithLifecycle()
    val modelReady = remember(modelDownload) { app.modelManager.isModelReady() }
    var menu by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val model = state.model

    BoxWithConstraints(Modifier.fillMaxSize().testTag("home_screen")) {
        // Landscape and enlarged fonts scroll the record workspace; the primary
        // navigation remains its Scaffold sibling and can never scroll offscreen.
        val previewHeight = (maxHeight - 400.dp).coerceIn(160.dp, 300.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeIntro(enabled = !state.busy, onLoad = onLoad)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp)) {
                    if (state.result.preview.isEmpty()) EmptyTranscript(Modifier.heightIn(min = previewHeight).padding(vertical = 20.dp))
                    else TranscriptReader(model?.document ?: controller.liveDocument, state.result,
                        Modifier.height(previewHeight).fillMaxWidth(), followTail = state.capture.active || state.result.running)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TranscriptTransferActions(state.result.store, enabled = !state.result.closing && !state.result.deleting,
                        modifier = Modifier.padding(vertical = 8.dp)) {
                        Box {
                            IconButton(onClick = { menu = true }, enabled = model != null && !state.busy,
                                modifier = Modifier.testTag("home_more")) {
                                Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.home_more))
                            }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.dialog_keep_text)) },
                                    enabled = state.result.preview.isNotEmpty(), onClick = { menu = false; model?.saveTranscriptToHistory() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.home_keep_audio)) },
                                    enabled = state.result.audio != null, onClick = { menu = false; model?.saveAudioToHistory() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.home_details)) },
                                    onClick = { menu = false; details = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.home_dismiss)) },
                                    onClick = { menu = false; controller.dismiss() })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.People, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_speakers), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.home_speakers_global), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(speakers, onCheckedChange = { enabled ->
                    // App-owned edit survives immediately leaving this tab. Each
                    // microphone session already snapshots the shared preferences.
                    app.applicationScope.launch { app.settingsRepository.setDiarizationEnabled(enabled) }
                }, modifier = Modifier.testTag("home_speakers"))
            }
            if (speakers && speakerCount != 1 && !speakerReady) {
                HomeNotice(stringResource(R.string.diarization_install_first), stringResource(R.string.home_speaker_setup), onSettings)
            }
            if (!modelReady) {
                HomeNotice(stringResource(R.string.home_model_missing), stringResource(R.string.recording_choose_model)) {
                    context.startActivity(RecordingRecovery.modelIntent(context))
                }
            }
            HomeCaptureControl(state, onRecord)
            PrivacyNote()
            HomeFeedback(state, onModels = { context.startActivity(RecordingRecovery.modelIntent(context)) }, onRetry = controller::retry)
            if (model != null && !state.busy && state.result.audio != null) {
                Text(stringResource(if (state.result.audio!!.temporary) R.string.home_temporary_audio else R.string.dialog_retained_info),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.result.preview.isNotEmpty() && !state.busy) {
                Text(stringResource(if (state.result.transcriptId == null) R.string.home_unsaved_text else R.string.home_saved_text),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (details && model != null) Dialog(onDismissRequest = { details = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TranscriptionDialog(model, onClose = { details = false; controller.dismiss() },
            onEmpty = { details = false; controller.dismiss() })
    }
}

/** Text establishes the row height; the local-file action uses that exact square. */
@Composable
private fun HomeIntro(enabled: Boolean, onLoad: () -> Unit) {
    Layout(content = {
        Column {
            Text(stringResource(R.string.home_new_transcript), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = onLoad, enabled = enabled, modifier = Modifier.testTag("home_load_audio")) {
            Icon(Icons.Outlined.FileUpload, stringResource(R.string.home_load_audio), Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 12.dp.roundToPx()
        val minimum = 48.dp.roundToPx()
        // Allocate a bounded square before measuring wrapped text. Two passes
        // avoid an onSizeChanged/recomposition sizing cycle at large font scales.
        var side = minimum
        repeat(4) {
            side = maxOf(minimum, measurables[0].minIntrinsicHeight((constraints.maxWidth - side - gap).coerceAtLeast(1)))
                .coerceAtMost(constraints.maxWidth / 2)
        }
        val text = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0,
            maxWidth = (constraints.maxWidth - side - gap).coerceAtLeast(0)))
        val button = measurables[1].measure(Constraints.fixed(side, side))
        layout(constraints.maxWidth, maxOf(text.height, button.height)) {
            text.placeRelative(0, 0)
            button.placeRelative(constraints.maxWidth - side, 0)
        }
    }
}

@Composable
private fun EmptyTranscript(modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Description, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.home_empty_title), Modifier.padding(top = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.home_empty_record), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        val hint = buildAnnotatedString {
            append(stringResource(R.string.home_empty_load_prefix)); append(" ")
            appendInlineContent("load", stringResource(R.string.home_load_audio))
            append(" "); append(stringResource(R.string.home_empty_load_suffix))
        }
        Text(hint, inlineContent = mapOf("load" to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Icons.Outlined.FileUpload, null, tint = MaterialTheme.colorScheme.primary)
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(stringResource(R.string.home_empty_result), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomeCaptureControl(state: HomeState, onRecord: () -> Unit) {
    val palette = LocalBrandPalette.current
    val phase = state.capture.phase
    val label = stringResource(when {
        state.canStop -> R.string.home_stop
        state.busy -> R.string.state_processing
        state.model != null -> R.string.home_new_recording
        else -> R.string.home_tap_record
    })
    val status = stringResource(when (phase) {
        HomeCapturePhase.STARTING -> R.string.home_starting
        HomeCapturePhase.RECORDING -> R.string.home_recording
        HomeCapturePhase.STOPPING -> R.string.capture_stopping
        HomeCapturePhase.PROCESSING -> R.string.capture_processing
        HomeCapturePhase.IDLE -> if (state.busy) R.string.state_processing else R.string.home_ready
    })
    val seconds = state.metrics.capturedSeconds.toLong()
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(Brush.horizontalGradient(palette.waveform))) {
        Row(Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp, top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color.White)
            if (state.capture.active) Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
        val scale = LocalDensity.current.fontScale
        if (state.busy && !state.canStop) HomeProgress(state)
        else AndroidView(factory = { context -> WaveformButton(context) },
            modifier = Modifier.fillMaxWidth().height((80 + 24 * scale.coerceAtLeast(1f)).dp).testTag("home_waveform"),
            update = { button ->
                button.applyPalette(palette)
                button.isIdle = phase != HomeCapturePhase.RECORDING && phase != HomeCapturePhase.STOPPING
                button.levels = state.metrics.waveform
                button.text = label
                button.contentDescription = label
                button.isEnabled = !state.busy || state.canStop
                button.setOnClickListener { onRecord() }
            })
    }
}

@Composable
private fun HomeProgress(state: HomeState) {
    val metrics = if (state.capture.active) state.metrics else state.result.capture
    val percent = if (metrics.phase == CapturePhase.PROCESSING) metrics.percent else state.result.progress
    Column(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.White.copy(alpha = .25f))
        else {
            Text(stringResource(if (metrics.phase == CapturePhase.PROCESSING) R.string.home_processed_percent else R.string.home_read_percent, percent),
                style = MaterialTheme.typography.bodySmall, color = Color.White)
            LinearProgressIndicator(progress = { percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth(),
                color = Color.White, trackColor = Color.White.copy(alpha = .25f))
        }
        if (metrics.phase == CapturePhase.PROCESSING) metrics.remainingSeconds?.takeIf { it.isFinite() && it > 0 }?.let {
            Text(stringResource(R.string.capture_eta, it.roundToInt().coerceAtLeast(1)), style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
    }
}

@Composable
private fun PrivacyNote() {
    val sentence = stringResource(R.string.home_privacy)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = MaterialTheme.typography.bodySmall
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.roundToPx() }
        val size = remember(width, density.fontScale, sentence, baseStyle) {
            // A short bounded search uses actual glyph widths. If even 9sp does
            // not fit, wrapping remains allowed; accessibility never gets clipped.
            (22 downTo 18).map { it / 2f }.firstOrNull { candidate ->
                measurer.measure(sentence, style = baseStyle.copy(fontSize = candidate.sp), softWrap = false).size.width <= width
            } ?: 9f
        }
        Text(sentence, Modifier.fillMaxWidth(), style = baseStyle.copy(fontSize = size.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomeNotice(message: String, action: String, onAction: () -> Unit) {
    Column {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun HomeFeedback(state: HomeState, onModels: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val metrics = if (state.capture.active) state.metrics else state.result.capture
    if (state.capture.active && metrics.modelPreparing) Text(stringResource(
        if (state.capture.phase == HomeCapturePhase.RECORDING) R.string.recording_model_loading else R.string.recording_model_loading_saved),
        style = MaterialTheme.typography.bodySmall)
    if (state.capture.active && metrics.recognitionFailure != null) {
        Text(stringResource(R.string.recording_recognition_unavailable), style = MaterialTheme.typography.bodySmall)
        Text(metrics.recognitionFailure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    val signal = when (metrics.signal) {
        CaptureSignal.LOW -> R.string.capture_no_signal
        CaptureSignal.NO_FRAMES -> R.string.capture_no_frames
        CaptureSignal.BLOCKED -> R.string.capture_blocked
        else -> null
    }
    if (state.canStop && signal != null) Text(stringResource(signal), style = MaterialTheme.typography.bodySmall)
    if (state.busy && !state.canStop) {
        if (metrics.recognition.active) Text(RecognitionStatusText.activity(context, metrics.recognition), style = MaterialTheme.typography.bodySmall)
    }
    val messages = listOfNotNull(state.message, state.result.message).distinct()
    messages.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (state.permissionDenied) TextButton(onClick = {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}")))
    }) { Text(stringResource(R.string.home_microphone_settings)) }
    if (messages.isNotEmpty() && !state.busy && !state.permissionDenied) {
        Row {
            if (state.result.audio != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.history_retry)) }
            TextButton(onClick = onModels) { Text(stringResource(R.string.recording_choose_model)) }
        }
    }
}
