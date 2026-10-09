package io.github.lrq3000.utterlane.transcribe

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
@Composable
internal fun TranscriptionProgressFooter(state: TranscriptionDialogState, statistics: Boolean) {
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
    if (operation == null && progress == null && state.message == null && !statistics) return
    val active = operation != null || state.running
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
    val stage = operation ?: progress?.let {
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
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("transcription_progress")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (stage != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Announce phase changes, not every percent/ETA refresh.
                Text(stringResource(stage), Modifier.weight(1f).testTag("transcription_stage").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                if (percent != null) Text(stringResource(if (progress?.estimatedTotal == true) R.string.progress_approximate_percent else R.string.progress_percent, percent),
                    Modifier.testTag("transcription_percentage"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            if (active && !complete) {
                val bar = Modifier.fillMaxWidth().height(4.dp).testTag("transcription_progress_bar")
                if (percent == null) LinearProgressIndicator(modifier = bar)
                else LinearProgressIndicator(progress = { percent / 100f }, modifier = bar)
            }
            if (progress != null && operation == null && !complete) {
                // Weighted text wraps under narrow/large-font configurations;
                // it never shrinks the text to keep a misleading single line.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active) {
                        val eta = progress.remainingSeconds?.takeIf { preparingStage == null }?.let {
                            context.getString(if (progress.additionalFinishing) R.string.progress_eta_finishing else R.string.progress_eta, etaDuration(context, it))
                        } ?: context.getString(if (progress.stage == FileProgressStage.TRANSCRIBING && progress.totalSamples == null)
                            R.string.progress_unknown_time else R.string.progress_estimating)
                        Text(eta, Modifier.weight(1f).testTag("transcription_eta"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Text(audioProgress(context, progress), Modifier.weight(1f).testTag("transcription_audio_progress"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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

private fun audioProgress(context: Context, progress: FileProgressSnapshot): String {
    val processed = DateUtils.formatElapsedTime(progress.processedSamples / 16000)
    val total = progress.totalSamples ?: return context.getString(R.string.progress_unknown_audio, processed)
    return context.getString(if (progress.estimatedTotal) R.string.progress_approximate_audio else R.string.progress_audio,
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
