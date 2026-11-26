package io.github.lrq3000.utterlane.transcribe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.util.StorageFailure
import java.io.File
import kotlinx.coroutines.*

/** Shared by queue rows and the exact-job reader. Opening, dismissing, and inspecting retention are read-only. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun QueueCancelDialog(id: String, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as UtterlaneApp
    val queue = app.transcriptionQueue
    val deletion = remember(app) { QueueAttemptDeletion(app.recordingHistory, app.transcriptHistory, queue.journal, queue::target, File(app.cacheDir, "transcripts")) }
    val dismiss by rememberUpdatedState(onDismiss)
    var plan by remember(id) { mutableStateOf<QueueDeleteQuestion?>(null) }
    var deleteAudio by remember(id) { mutableStateOf(false) }
    var busy by remember(id) { mutableStateOf(false) }
    var message by remember(id) { mutableStateOf<String?>(null) }
    var reload by remember(id) { mutableIntStateOf(0) }
    val mounted = remember(id) { mutableStateOf(true) }
    DisposableEffect(id) { onDispose { mounted.value = false } }
    LaunchedEffect(id, reload) {
        try {
            queue.initialized()
            plan = withContext(Dispatchers.IO) { deletion.question(id) }
            if (plan == null) dismiss()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = StorageFailure.userMessage(app, e) ?: app.getString(R.string.queue_cancel_failed) }
    }
    AlertDialog(
        modifier = Modifier.testTag("queue_cancel_dialog").semantics { testTagsAsResourceId = true },
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.queue_cancel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) Text(stringResource(R.string.dialog_deleting))
                else plan?.let { selection ->
                    Text(stringResource(R.string.queue_cancel_message, selection.label))
                    if (selection.canDeleteAudio) Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("queue_cancel_audio")
                            .toggleable(deleteAudio, role = Role.Checkbox) { deleteAudio = it },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteAudio, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.queue_cancel_audio))
                    }
                    Text(stringResource(R.string.dialog_delete_irreversible))
                } ?: if (message == null) Text(stringResource(R.string.dialog_checking_items)) else Unit
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(onClick = { dismiss() }, enabled = !busy, modifier = Modifier.testTag("queue_cancel_dismiss")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            val selected = plan
            if (selected != null) TextButton(onClick = {
                if (busy) return@TextButton
                busy = true
                message = null
                val includeAudio = deleteAudio && selected.canDeleteAudio
                // Once confirmed, leaving the screen must not cancel after stopping the producer but before retiring its output and notification.
                app.applicationScope.launch {
                    try {
                        val fresh = queue.withDisposition { deletion.confirm(selected, includeAudio, queue::stopForDeletion, queue::acknowledge) }
                        if (mounted.value) {
                            if (fresh == null) dismiss()
                            else {
                                plan = fresh; deleteAudio = false
                                message = app.getString(R.string.dialog_deletion_changed)
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val failure = StorageFailure.userMessage(app, e) ?: app.getString(R.string.queue_cancel_failed)
                        android.util.Log.w("QueueCancel", "Could not retire the confirmed attempt", e)
                        if (mounted.value) { message = failure; plan = null; deleteAudio = false }
                        else ActionFeedback.show(app, failure)
                    } finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.testTag("queue_cancel_confirm"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.action_delete))
            } else if (message != null) TextButton(onClick = { message = null; reload++ }) { Text(stringResource(R.string.history_retry)) }
        })
}
