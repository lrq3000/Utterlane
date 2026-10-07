package io.github.lrq3000.utterlane.history

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

fun HistoryRetention.label(): Int = when (this) {
    HistoryRetention.NONE -> R.string.history_immediate
    HistoryRetention.HOUR -> R.string.history_hour
    HistoryRetention.SIX_HOURS -> R.string.history_six_hours
    HistoryRetention.DAY -> R.string.history_day
    HistoryRetention.WEEK -> R.string.history_week
    HistoryRetention.MONTH -> R.string.history_month
    HistoryRetention.THREE_MONTHS -> R.string.history_three_months
    HistoryRetention.FOREVER -> R.string.history_forever
}

@Composable
fun HistorySettings() {
    val app = UtterlaneApp.instance
    val settings = app.settingsRepository
    val scope = rememberCoroutineScope()
    val audioEnabled by settings.audioHistoryEnabled.collectAsStateWithLifecycle(initialValue = true)
    val audioRetention by settings.audioHistoryRetention.collectAsStateWithLifecycle(initialValue = HistoryRetention.HOUR)
    val textEnabled by settings.transcriptHistoryEnabled.collectAsStateWithLifecycle(initialValue = false)
    val textRetention by settings.transcriptHistoryRetention.collectAsStateWithLifecycle(initialValue = HistoryRetention.DAY)
    var browse by remember { mutableStateOf<Boolean?>(null) }
    HistoryPolicySetting(stringResource(R.string.history_auto_audio), audioEnabled, audioRetention,
        enabled = { scope.launch { settings.setAudioHistoryEnabled(it) } }, duration = { scope.launch { settings.setAudioHistoryRetention(it) } })
    HistoryPolicySetting(stringResource(R.string.history_auto_text), textEnabled, textRetention,
        enabled = { scope.launch { settings.setTranscriptHistoryEnabled(it) } }, duration = { scope.launch { settings.setTranscriptHistoryRetention(it) } })
    Text(stringResource(R.string.history_policy_description), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { browse = false }) { Text(stringResource(R.string.history_title)) }
    TextButton(onClick = { browse = true }) { Text(stringResource(R.string.transcript_history_title)) }
    TextButton(onClick = { io.github.lrq3000.utterlane.service.TranscriptRecovery.open(app) }) { Text(stringResource(R.string.stream_recover)) }
    browse?.let { transcripts -> HistoryDialog(transcripts = transcripts) { browse = null } }
}

@Composable
private fun HistoryPolicySetting(title: String, selected: Boolean, retention: HistoryRetention,
    enabled: (Boolean) -> Unit, duration: (HistoryRetention) -> Unit) {
    var choose by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked = selected, onCheckedChange = enabled) })
    ListItem(headlineContent = { Text(stringResource(R.string.history_prune_after)) },
        supportingContent = { Text(stringResource(retention.label())) },
        trailingContent = { TextButton(onClick = { choose = true }) { Text(stringResource(R.string.history_change)) } })
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            HistoryRetention.entries.forEach { choice ->
                TextButton(onClick = { duration(choice); choose = false }) {
                    RadioButton(selected = retention == choice, onClick = null)
                    Text(stringResource(choice.label()))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { choose = false }) { Text(stringResource(R.string.transcribe_close)) } })
}

private data class HistoryRow(val id: String, val created: Long, val detail: String, val retention: RetentionMark,
    val recovery: Boolean = false, val temporary: Boolean = false)

/** Both browsers share controls and pagination; only their repository/data labels differ. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HistoryDialog(transcripts: Boolean = false, onDismiss: () -> Unit) {
    val app = UtterlaneApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by (if (transcripts) app.transcriptHistory.revision else app.recordingHistory.revision).collectAsStateWithLifecycle()
    var rows by remember { mutableStateOf(emptyList<HistoryRow>()) }
    var page by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision, page) {
        try {
            rows = withContext(Dispatchers.IO) {
                if (transcripts) app.transcriptHistory.list(page).map { entry ->
                    val preview = entry.file.reader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(160)
                        val count = reader.read(buffer)
                        if (count < 0) "" else String(buffer, 0, count)
                    }
                    HistoryRow(entry.id, entry.created, entry.model + "\n" + preview, entry.retention)
                } else {
                    app.recordingHistory.initialize()
                    app.recordingHistory.list(page).map { entry -> HistoryRow(entry.id, entry.started,
                        context.getString(R.string.history_duration, entry.seconds), entry.retention, entry.needsRecovery, entry.temporary) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { message = e.message }
    }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { withContext(Dispatchers.IO) { block() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { message = e.message }
    } }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (transcripts) R.string.transcript_history_title else R.string.history_title)) },
        text = { Column(Modifier.semantics { testTagsAsResourceId = true }) {
            message?.let { Text(it) }
            if (rows.isEmpty()) Text(stringResource(if (transcripts) R.string.transcript_history_empty else R.string.history_empty))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(rows, key = { it.id }) { entry ->
                    Column(Modifier.padding(vertical = 6.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium).padding(12.dp)) {
                        Row {
                            Text(DateFormat.getDateTimeInstance().format(Date(entry.created)), Modifier.weight(1f))
                            val pinState = stringResource(if (entry.retention.pinned) R.string.history_pinned else R.string.history_not_pinned)
                            IconButton(onClick = { action {
                                // The persisted duration, not Compose's initial display
                                // value, decides Immediate-unpin protection.
                                val duration = if (transcripts) app.settingsRepository.transcriptHistoryRetention.first() else app.settingsRepository.audioHistoryRetention.first()
                                if (transcripts) app.transcriptHistory.setPinned(entry.id, !entry.retention.pinned, duration, app.historyCleanup.launchToken)
                                else app.recordingHistory.setPinned(entry.id, !entry.retention.pinned, duration, app.historyCleanup.launchToken)
                                withContext(Dispatchers.Main) { message = context.getString(when {
                                    !entry.retention.pinned -> R.string.history_pinned
                                    duration == HistoryRetention.NONE -> R.string.history_unpin_immediate
                                    else -> R.string.history_unpinned_now
                                }) }
                            } }, modifier = Modifier.testTag("history_pin_${entry.id}").semantics { stateDescription = pinState }) {
                                Icon(if (entry.retention.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = stringResource(if (entry.retention.pinned) R.string.history_unpin else R.string.history_pin),
                                    tint = if (entry.retention.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(entry.detail)
                        if (entry.retention.pinned) Text(stringResource(R.string.history_pinned), style = MaterialTheme.typography.bodySmall)
                        else if (entry.retention.holdForLaunch != null) Text(stringResource(R.string.history_unpin_immediate), style = MaterialTheme.typography.bodySmall)
                        else if (entry.temporary) Text(stringResource(R.string.history_temporary_recovery), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                context.startActivity(Intent(context, TranscribeActivity::class.java)
                                    .putExtra(if (transcripts) TranscribeActivity.EXTRA_TRANSCRIPT_ID else TranscribeActivity.EXTRA_AUDIO_ID, entry.id)
                                    .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true))
                            }) { Text(stringResource(R.string.history_open)) }
                            TextButton(onClick = {
                                if (!transcripts) app.audioPlayback.stopAudio(entry.id)
                                action {
                                if (transcripts) app.transcriptHistory.delete(entry.id) else {
                                    app.recordingHistory.delete(entry.id)
                                    RecordingRecovery.dismissNotification(context, entry.id)
                                }
                            } }) { Text(stringResource(if (transcripts) R.string.dialog_delete_text else if (entry.temporary) R.string.dialog_discard else R.string.history_delete)) }
                        }
                    }
                }
            }
            Row {
                TextButton(enabled = page > 0, onClick = { page-- }) { Text(stringResource(R.string.stream_previous)) }
                TextButton(enabled = rows.size == 30, onClick = { page++ }) { Text(stringResource(R.string.stream_next)) }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transcribe_close)) } })
}
