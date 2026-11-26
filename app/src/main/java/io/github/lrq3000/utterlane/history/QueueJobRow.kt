package io.github.lrq3000.utterlane.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.transcribe.QueueNotifications
import io.github.lrq3000.utterlane.transcribe.QueueRecord
import io.github.lrq3000.utterlane.transcribe.QueueStage
import io.github.lrq3000.utterlane.transcribe.label

/** Unlike an ordinary file, a live take still has a producer after the microphone closes. */
internal fun QueueStage.listLabel() = if (this == QueueStage.DRAINING) R.string.queue_list_draining else label()

@Composable
internal fun QueueJobRow(job: QueueRecord, onCancel: () -> Unit, preview: String? = null,
    modifier: Modifier = Modifier, recovered: Boolean = false, onNavigated: () -> Unit = {}, metadata: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    HistoryListEntry(
        title = preview?.takeIf { it.isNotBlank() } ?: job.label,
        icon = when {
            recovered -> Icons.Default.Restore
            job.stage == QueueStage.CAPTURING || job.stage == QueueStage.DRAINING -> Icons.Default.Mic
            else -> Icons.Default.Description
        },
        iconDescription = if (recovered) stringResource(R.string.history_recovered_entry) else null,
        modifier = modifier.testTag("queue_job_${job.id}"),
        onOpen = { context.startActivity(QueueNotifications.intent(context, job.id)); onNavigated() },
        action = {
            if (job.stage.canCancelFromList) IconButton(onClick = onCancel, modifier = Modifier.testTag("queue_cancel_${job.id}")) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.queue_list_delete_attempt, job.sequence, job.label),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    ) {
        val stage = stringResource(job.stage.listLabel())
        Text(stringResource(R.string.queue_list_stage, job.sequence, if (preview.isNullOrBlank()) stage
            else stringResource(R.string.queue_monitor_status, stage, job.label)))
        metadata()
    }
}
