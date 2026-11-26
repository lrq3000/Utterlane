package io.github.lrq3000.utterlane.transcribe

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.RecognitionStage
import io.github.lrq3000.utterlane.ui.RecognitionStatusText
import kotlin.math.ceil

/** Design D: A's quiet linear treatment at C's bottom reader edge. Operational
 * details share this bounded area so neither errors nor completion move its top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TranscriptionProgressFooter(state: TranscriptionDialogState, statistics: Boolean, jobControls: QueueJobControls? = null,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val progress = state.fileProgress
    val operation = when {
        state.deleting -> R.string.dialog_deleting
        state.closing -> R.string.dialog_closing
        state.saving -> R.string.dialog_saving
        state.importing -> R.string.dialog_importing
        state.checkingDeletion -> R.string.dialog_checking_items
        else -> null
    }
    if (operation == null && progress == null && state.message == null && !statistics && jobControls == null) return
    val queueStage = jobControls?.stage
    // Only active recognition owns an ETA. An old snapshot is still useful as a position, but must not suggest that paused work is advancing.
    val active = (operation != null || state.running) && (queueStage == null || queueStage == QueueStage.PROCESSING)
    val complete = operation == null && progress?.stage == FileProgressStage.COMPLETE
    val percent = progress?.percent?.takeIf { operation == null }
    val preparingStage = when (state.capture.recognition.stage) {
        RecognitionStage.QUEUED -> R.string.recognition_queued
        RecognitionStage.CONNECTING -> R.string.recognition_connecting
        RecognitionStage.LOADING -> R.string.recognition_loading
        RecognitionStage.WARMUP -> R.string.recognition_warmup
        RecognitionStage.SPEAKER_LOAD -> R.string.recognition_speaker_load
        else -> null
    }.takeIf { state.capture.recognition.active }
    val queueStatus = queueStage?.takeIf { it != QueueStage.PROCESSING && (!it.reviewable || it == QueueStage.STOPPED) }
    val stage = queueStatus?.label() ?: operation ?: progress?.let {
        when (it.stage) {
            FileProgressStage.PREPARING -> preparingStage ?: R.string.progress_preparing
            FileProgressStage.TRANSCRIBING -> preparingStage ?: R.string.progress_transcribing
            FileProgressStage.FINALIZING -> R.string.progress_finalizing
            FileProgressStage.SPEAKERS -> R.string.progress_speakers
            FileProgressStage.SAVING -> R.string.dialog_saving
            FileProgressStage.COMPLETE -> R.string.progress_complete
            FileProgressStage.FAILED -> R.string.progress_interrupted
            FileProgressStage.CANCELLED -> R.string.progress_cancelled
        }
    } ?: queueStage?.label()
    val hasTransport = queueStage?.hasProcessingControls == true
    Column(modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("transcription_progress")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (stage != null) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Announce phase changes, not every percent/ETA refresh.
                Text(stringResource(stage), Modifier.testTag("transcription_stage").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                // Wrap the pair as a unit when space permits; the duration itself may wrap at large system font scales.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (percent != null) Text(stringResource(if (progress?.estimatedTotal == true) R.string.progress_approximate_percent else R.string.progress_percent, percent),
                        Modifier.testTag("transcription_percentage"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    if (progress != null && operation == null && (!complete || progress.totalSamples != null))
                        Text(audioProgress(context, progress), Modifier.testTag("transcription_audio_progress"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val bar: @Composable () -> Unit = {
                val modifier = Modifier.fillMaxWidth().height(4.dp).testTag("transcription_progress_bar")
                if (percent == null) LinearProgressIndicator(modifier = modifier)
                else LinearProgressIndicator(progress = { percent / 100f }, modifier = modifier)
            }
            if (jobControls != null && hasTransport) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val showBar = active && !complete
                if (showBar && maxWidth >= 230.dp) {
                    val controlsWidth = maxWidth - 64.dp
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { bar() }
                        QueueProcessingControls(jobControls, canRestart = state.audio != null, modifier = Modifier.widthIn(max = controlsWidth))
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showBar) bar()
                    QueueProcessingControls(jobControls, canRestart = state.audio != null)
                }
            } else if (active && !complete) bar()
            if (active && progress != null && operation == null && !complete) {
                val eta = progress.remainingSeconds?.takeIf { preparingStage == null }?.let {
                    context.getString(if (progress.additionalFinishing) R.string.progress_eta_finishing else R.string.progress_eta, etaDuration(context, it))
                } ?: context.getString(if (progress.stage == FileProgressStage.TRANSCRIBING && progress.totalSamples == null)
                    R.string.progress_unknown_time else R.string.progress_estimating)
                Text(eta, Modifier.testTag("transcription_eta"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (statistics) {
                RecognitionStatusText.activity(context, state.capture.recognition).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(RecognitionStatusText.backlog(context, state.capture), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val QueueStage.hasProcessingControls: Boolean
    get() = this == QueueStage.WAITING || this == QueueStage.PROCESSING || this == QueueStage.PAUSED || this == QueueStage.STOPPED

/** These callbacks operate on this queue job, never on the audio player or microphone. Transition stages cannot accept another transport command. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QueueProcessingControls(jobControls: QueueJobControls, canRestart: Boolean = true, modifier: Modifier = Modifier) {
    val stage = jobControls.stage
    if (!stage.hasProcessingControls) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (jobControls.canContinue)
            DialogAction(Icons.Outlined.PlayArrow, stringResource(R.string.reader_continue_transcription), "queue_continue", onClick = jobControls.resume)
        else if (stage != QueueStage.STOPPED)
            DialogAction(Icons.Outlined.Pause, stringResource(R.string.reader_pause_transcription), "queue_pause", onClick = jobControls.pause)
        if (stage != QueueStage.STOPPED)
            DialogAction(Icons.Outlined.Stop, stringResource(R.string.reader_stop_transcription), "queue_stop", onClick = jobControls.stop)
        if (canRestart)
            DialogAction(Icons.Outlined.RestartAlt, stringResource(R.string.reader_restart_transcription), "queue_restart", onClick = jobControls.restart)
    }
}

private fun audioProgress(context: Context, progress: FileProgressSnapshot): String {
    val processed = DateUtils.formatElapsedTime(progress.processedSamples / 16000)
    val total = progress.totalSamples ?: return context.getString(R.string.progress_unknown_audio, processed)
    return context.getString(if (progress.estimatedTotal) R.string.reader_estimated_duration else R.string.reader_known_duration,
        processed, DateUtils.formatElapsedTime(total / 16000))
}

private fun etaDuration(context: Context, seconds: Double): String {
    val rounded = ceil(seconds).toLong().coerceAtLeast(1)
    return when {
        rounded >= 3600 -> context.getString(R.string.progress_hours_minutes, rounded / 3600, (rounded % 3600) / 60)
        rounded >= 60 -> context.getString(R.string.progress_minutes_seconds, rounded / 60, rounded % 60)
        else -> context.getString(R.string.progress_seconds, rounded)
    }
}
