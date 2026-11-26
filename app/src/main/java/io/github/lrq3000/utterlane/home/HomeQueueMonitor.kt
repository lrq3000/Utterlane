package io.github.lrq3000.utterlane.home

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.QueueHistoryIndex
import io.github.lrq3000.utterlane.history.QueueJobRow
import io.github.lrq3000.utterlane.history.listLabel
import io.github.lrq3000.utterlane.transcribe.QueueCancelDialog
import io.github.lrq3000.utterlane.transcribe.QueueStage
import io.github.lrq3000.utterlane.transcribe.TranscriptionQueue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HomeQueueMonitor(recording: Boolean) {
    val app = UtterlaneApp.instance
    val jobs by app.transcriptionQueue.rows.collectAsStateWithLifecycle()
    val index = remember(jobs) { QueueHistoryIndex(jobs) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var cancelId by rememberSaveable { mutableStateOf<String?>(null) }
    val monitor = index.monitorJob
    if (monitor != null) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("home_queue"),
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.queue_monitor_heading, index.monitorCount), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.queue_monitor_status, stringResource(monitor.stage.listLabel()), monitor.label),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (monitor.stage == QueueStage.PROCESSING) MonitorPercent(app.transcriptionQueue, monitor.id)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
    // An open monitor stays usable when its last active job finishes or pauses. The compact Home widget itself goes away immediately.
    if (expanded) {
        val context = LocalContext.current
        AlertDialog(onDismissRequest = { expanded = false }, modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text(stringResource(R.string.queue_title)) },
            text = {
                if (index.monitorRows.isEmpty()) Text(stringResource(R.string.queue_list_empty))
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("home_queue_list")) {
                    items(index.monitorRows, key = { it.id }) { job ->
                        QueueJobRow(job, onCancel = { cancelId = job.id }, onNavigated = { expanded = false })
                        HorizontalDivider()
                    }
                    if (!recording && monitor != null) item { QueueRetentionReminder() }
                }
            }, dismissButton = {
                val admission by app.transcriptionQueue.admission.status.collectAsStateWithLifecycle()
                if (!recording && monitor != null && !admission.needsResume(monitor)) {
                    TextButton({ (context as? Activity)?.moveTaskToBack(true) }, modifier = Modifier.testTag("home_background")) {
                        Text(stringResource(R.string.queue_background))
                    }
                }
            }, confirmButton = {
                TextButton({ expanded = false }, modifier = Modifier.testTag("home_queue_close")) { Text(stringResource(R.string.transcribe_close)) }
            })
    }
    cancelId?.let { QueueCancelDialog(it) { cancelId = null } }
}

/** Observe only the displayed attempt's measured percentage; do not republish the transcript or waveform on Home. */
@Composable
private fun MonitorPercent(queue: TranscriptionQueue, id: String) {
    val active by queue.active.collectAsStateWithLifecycle()
    val model = active?.takeIf { it.first == id }?.second ?: return
    key(model) {
        val percent by remember(model) {
            model.state.map { state -> state.fileProgress?.let { p -> p.percent?.let { it to p.estimatedTotal } } }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = null)
        percent?.let { (value, estimated) ->
            Text(stringResource(if (estimated) R.string.progress_approximate_percent else R.string.progress_percent, value),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun QueueRetentionReminder() {
    val settings = UtterlaneApp.instance.settingsRepository
    val audio by settings.audioHistoryEnabled.collectAsStateWithLifecycle(true)
    val text by settings.transcriptHistoryEnabled.collectAsStateWithLifecycle(true)
    val audioRetention by settings.audioHistoryRetention.collectAsStateWithLifecycle(HistoryRetention.DEFAULT)
    val textRetention by settings.transcriptHistoryRetention.collectAsStateWithLifecycle(HistoryRetention.DAY)
    if (!audio || !text || audioRetention == HistoryRetention.NONE || textRetention == HistoryRetention.NONE) {
        Text(stringResource(R.string.queue_after_close), style = MaterialTheme.typography.bodySmall)
    }
}
