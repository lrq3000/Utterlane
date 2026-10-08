package io.github.lrq3000.utterlane.transcribe

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.history.HistoryDeletionTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DialogAction(icon: ImageVector, label: String, tag: String, enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label }) {
            Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
    }
}

/** One intrinsic measurement per title/style/width. Only the title shrinks;
 * 48 dp controls keep their full hit areas, including on a 320 dp phone. */
@Composable
internal fun AutoFitDialogTitle(title: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(modifier) {
        val natural = remember(title, style, measurer) { measurer.measure(title, style, softWrap = false, maxLines = 1).size.width }
        val ratio = (constraints.maxWidth.toFloat() / natural.coerceAtLeast(1)).coerceIn(0.01f, 1f)
        Text(title, style = style.copy(fontSize = (22 * ratio).sp), maxLines = 1, softWrap = false,
            modifier = Modifier.testTag("dialog_title"))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DialogDeletionControl(model: TranscriptionDialogModel, state: TranscriptionDialogState, busy: Boolean) {
    val request = state.deletion
    Box {
        DialogAction(Icons.Outlined.Delete, stringResource(R.string.history_delete), "dialog_delete",
            !busy && !state.checkingDeletion, tint = MaterialTheme.colorScheme.error, onClick = model::requestDeletion)
        DropdownMenu(expanded = request != null && request.target == null, onDismissRequest = model::dismissDeletionMenu,
            modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            request?.plan?.choices?.forEach { target ->
                val label = when (target) {
                    HistoryDeletionTarget.AUDIO -> stringResource(R.string.dialog_delete_audio_choice)
                    HistoryDeletionTarget.TRANSCRIPTS -> if (request.plan.allLinked)
                        stringResource(R.string.dialog_delete_linked_choice, request.plan.transcriptIds.size)
                        else stringResource(R.string.dialog_delete_current_choice)
                    HistoryDeletionTarget.BOTH -> stringResource(R.string.dialog_delete_both_choice)
                }
                DropdownMenuItem(text = { Text(label) }, onClick = { model.chooseDeletion(target) },
                    modifier = Modifier.testTag("delete_choice_${target.name.lowercase()}"))
            }
        }
    }
    request?.target?.let { target ->
        val count = request.plan.transcriptIds.size
        val title = when (target) {
            HistoryDeletionTarget.AUDIO -> stringResource(R.string.dialog_delete_audio_title)
            HistoryDeletionTarget.TRANSCRIPTS -> if (request.plan.allLinked)
                pluralStringResource(R.plurals.dialog_delete_all_title, count, count)
                else stringResource(R.string.dialog_delete_current_title)
            HistoryDeletionTarget.BOTH -> if (request.plan.allLinked)
                pluralStringResource(R.plurals.dialog_delete_all_both_title, count, count)
                else stringResource(R.string.dialog_delete_current_both_title)
        }
        AlertDialog(onDismissRequest = model::cancelDeletion,
            modifier = Modifier.testTag("delete_confirmation").semantics { testTagsAsResourceId = true },
            icon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(title) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.dialog_delete_irreversible))
                    if (request.plan.allLinked && target != HistoryDeletionTarget.AUDIO)
                        Text(stringResource(R.string.dialog_delete_individual_info))
                    else if (target != HistoryDeletionTarget.AUDIO)
                        Text(stringResource(R.string.dialog_delete_current_info))
                }
            }, dismissButton = {
                TextButton(onClick = model::cancelDeletion, modifier = Modifier.testTag("delete_cancel")) { Text(stringResource(R.string.action_cancel)) }
            }, confirmButton = {
                TextButton(onClick = model::confirmDeletion, modifier = Modifier.testTag("delete_confirm"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.history_delete)) }
            })
    }
}
