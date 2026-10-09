package io.github.lrq3000.utterlane.transcribe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun DialogExitConfirmation(model: TranscriptionDialogModel, state: TranscriptionDialogState) {
    val request = state.exitRequest ?: return
    AlertDialog(onDismissRequest = model::cancelExit,
        modifier = Modifier.testTag("exit_confirmation").semantics { testTagsAsResourceId = true },
        title = { Text(stringResource(R.string.dialog_exit_title)) },
        text = { Text(stringResource(when {
            request.audioId != null && request.transcriptId != null -> R.string.dialog_exit_both
            request.audioId != null -> R.string.dialog_exit_audio
            else -> R.string.dialog_exit_text
        })) },
        confirmButton = {
            // One wrapping action group keeps all three choices legible at large
            // font sizes without the platform arranging a two-row dismiss slot.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { model.confirmExit(pin = false) }, enabled = !state.closing,
                    modifier = Modifier.testTag("exit_discard"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.dialog_discard))
                }
                TextButton(onClick = model::cancelExit, enabled = !state.closing,
                    modifier = Modifier.testTag("exit_back")) { Text(stringResource(R.string.dialog_exit_back)) }
                TextButton(onClick = { model.confirmExit(pin = true) }, enabled = !state.closing,
                    modifier = Modifier.testTag("exit_pin")) { Text(stringResource(R.string.dialog_exit_pin)) }
            }
        })
}
