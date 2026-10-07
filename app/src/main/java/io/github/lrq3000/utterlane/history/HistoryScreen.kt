package io.github.lrq3000.utterlane.history

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.transcribe.TranscribeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import java.io.Closeable
import java.text.DateFormat
import java.util.Date

fun HistoryRetention.label(): Int = when (this) {
    HistoryRetention.NONE -> R.string.history_none
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
    val scope = rememberCoroutineScope()
    val retention by app.settingsRepository.historyRetention.collectAsStateWithLifecycle(initialValue = HistoryRetention.DEFAULT)
    var choose by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf(false) }
    val revision by app.recordingHistory.revision.collectAsStateWithLifecycle()
    var recoveryCount by remember { mutableStateOf(0) }
    LaunchedEffect(revision) {
        recoveryCount = withContext(Dispatchers.IO) { app.recordingHistory.recoveryCount() }
    }
    ListItem(headlineContent = { Text(stringResource(R.string.history_retention)) },
        supportingContent = { Text(stringResource(retention.label())) },
        trailingContent = { TextButton(onClick = { choose = true }) { Text(stringResource(R.string.history_change)) } })
    Text(stringResource(R.string.history_description), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { browse = true }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.history_title)) }
    if (recoveryCount > 0) TextButton(onClick = { browse = true }) {
        Text(stringResource(R.string.recording_recovery_count, recoveryCount))
    }
    TextButton(onClick = { io.github.lrq3000.utterlane.service.TranscriptRecovery.open(app) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.stream_recover)) }
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text(stringResource(R.string.history_retention)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { HistoryRetention.entries.forEach { option ->
            Row { RadioButton(selected = option == retention, onClick = { scope.launch { app.settingsRepository.setHistoryRetention(option) }; choose = false })
                TextButton(onClick = { scope.launch { app.settingsRepository.setHistoryRetention(option) }; choose = false }) { Text(stringResource(option.label())) } }
        } } }, confirmButton = { TextButton(onClick = { choose = false }) { Text(stringResource(R.string.overlay_cancel)) } })
    if (browse) HistoryDialog { browse = false }
}

@Composable
fun HistoryDialog(onDismiss: () -> Unit) {
    val app = UtterlaneApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = app.recordingHistory
    val retention by app.settingsRepository.historyRetention.collectAsStateWithLifecycle(initialValue = HistoryRetention.DEFAULT)
    val revision by history.revision.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf(emptyList<HistoryEntry>()) }
    var page by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val playback = remember { HistoryPlayback(context, history) { message = it } }
    DisposableEffect(Unit) { onDispose { playback.stop() } }
    LaunchedEffect(page, refresh, retention, revision) {
        try {
            entries = withContext(Dispatchers.IO) {
                history.initialize()
                history.list(page)
            }
        }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { message = e.message }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.history_title)) },
        text = {
            Column {
                Text(stringResource(R.string.recording_recovery_explanation))
                TextButton(onClick = { RecordingRecovery.openModels(context) }) { Text(stringResource(R.string.recording_choose_model)) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (entries.isEmpty()) Text(stringResource(R.string.history_empty))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Column(Modifier.padding(vertical = 6.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                            .padding(12.dp)) {
                            Text(DateFormat.getDateTimeInstance().format(Date(entry.started)))
                            Text(stringResource(R.string.history_duration, entry.seconds))
                            Text(stringResource(when (entry.status) { "saved" -> R.string.history_saved; "failed" -> R.string.history_failed; else -> R.string.history_interrupted }))
                            if (entry.needsRecovery) Text(stringResource(R.string.recording_recovery_pending))
                            Row {
                                TextButton(onClick = { playback.play(entry) }) { Text(stringResource(R.string.history_play)) }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            val exported = withContext(Dispatchers.IO) { HistoryExports.create(history, entry.id, java.io.File(context.cacheDir, "history-exports")) }
                                            val uris = ArrayList(exported.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
                                            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                                                type = "audio/wav"
                                                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                if (uris.isNotEmpty()) clipData = ClipData.newRawUri("Recording", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                                            }
                                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.transcribe_share_title)))
                                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                        catch (e: Exception) { message = e.message }
                                    }
                                }) { Text(stringResource(R.string.transcribe_share)) }
                            }
                            Row {
                                TextButton(onClick = { context.startActivity(Intent(context, TranscribeActivity::class.java).putExtra("history_id", entry.id)
                                    .putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true)) }) { Text(stringResource(R.string.history_retranscribe)) }
                                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { history.delete(entry.id) }; refresh++ } }) { Text(stringResource(R.string.history_delete)) }
                            }
                        }
                    }
                }
                Row {
                    TextButton(enabled = page > 0, onClick = { page-- }) { Text(stringResource(R.string.stream_previous)) }
                    TextButton(enabled = entries.size == 30, onClick = { page++ }) { Text(stringResource(R.string.stream_next)) }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.overlay_cancel)) } })
}

/** A lease covers sequential playback of every hourly WAV part. */
private class HistoryPlayback(private val context: Context, private val history: RecordingHistory, private val onError: (String) -> Unit) {
    private var player: MediaPlayer? = null
    private var lease: Closeable? = null
    private var id: String? = null
    private var generation = 0

    fun play(entry: HistoryEntry) {
        val same = id == entry.id
        stop()
        if (same) return
        val token = generation
        UtterlaneApp.instance.applicationScope.launch {
            try {
                val acquired = withContext(Dispatchers.IO) { history.acquire(entry.id) }
                if (generation != token) { withContext(Dispatchers.IO) { acquired.close() }; return@launch }
                lease = acquired; id = entry.id
                startPart(entry, 0)
            } catch (e: Exception) { onError(e.message ?: context.getString(R.string.transcribe_error_failed)); stop() }
        }
    }
    private fun startPart(entry: HistoryEntry, part: Int) {
        player?.release()
        if (part >= entry.parts) { stop(); return }
        player = MediaPlayer().apply {
            setDataSource(entry.part(part).absolutePath)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { startPart(entry, part + 1) }
            setOnErrorListener { _, what, extra -> onError("Playback error $what/$extra"); stop(); true }
            prepareAsync()
        }
    }
    fun stop() {
        generation++
        player?.release(); player = null; id = null
        val previous = lease; lease = null
        UtterlaneApp.instance.applicationScope.launch(Dispatchers.IO) { previous?.close() }
    }
}
