package io.github.lrq3000.utterlane.transcribe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.history.ImportRetentionCategory
import io.github.lrq3000.utterlane.history.ImportRetentionChoice

/** Also rendered for Home's pending candidate, before it owns any replacement
 * input. It must never depend on the accepted result or on !HomeState.busy. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImportRetentionQuestion(model: TranscriptionDialogModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val request = state.importQuestion ?: return
    var remember by rememberSaveable(model.playbackOwner) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = model::cancelImport,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("import_retention_question"),
        title = { Text(stringResource(R.string.import_retention_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                request.metadata.displayName?.let { Text(it, Modifier.padding(bottom = 8.dp)) }
                Text(stringResource(if (request.category == ImportRetentionCategory.DURATION)
                    R.string.import_retention_duration else R.string.import_retention_size))
                Text(stringResource(R.string.import_retention_explanation), Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("import_remember")
                    .toggleable(remember, role = Role.Checkbox, onValueChange = { remember = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = null)
                    Text(stringResource(R.string.import_retention_remember), Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { model.chooseImportRetention(ImportRetentionChoice.KEEP, remember) },
                    modifier = Modifier.testTag("import_keep_audio")) { Text(stringResource(R.string.import_keep_audio)) }
                TextButton(onClick = { model.chooseImportRetention(ImportRetentionChoice.DISCARD, remember) },
                    modifier = Modifier.testTag("import_transcript_only")) { Text(stringResource(R.string.import_transcript_only)) }
            }
        },
        dismissButton = {
            TextButton(onClick = model::cancelImport, modifier = Modifier.testTag("import_cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        })
}
