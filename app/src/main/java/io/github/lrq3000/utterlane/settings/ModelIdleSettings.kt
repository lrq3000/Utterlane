package io.github.lrq3000.utterlane.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.ModelIdleTimeout

private fun ModelIdleTimeout.label(): Int = when (this) {
    ModelIdleTimeout.IMMEDIATE -> R.string.model_idle_immediate
    ModelIdleTimeout.FIVE_MINUTES -> R.string.model_idle_five_minutes
    ModelIdleTimeout.TWENTY_MINUTES -> R.string.model_idle_twenty_minutes
    ModelIdleTimeout.HOUR -> R.string.model_idle_hour
    ModelIdleTimeout.THREE_HOURS -> R.string.model_idle_three_hours
    ModelIdleTimeout.DAY -> R.string.model_idle_day
    ModelIdleTimeout.NEVER -> R.string.model_idle_never
}

@Composable
fun ModelIdleSettings(timeout: ModelIdleTimeout, onSelected: (ModelIdleTimeout) -> Unit) {
    var choose by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.model_idle_title)) },
        supportingContent = { Text(stringResource(timeout.label())) },
        trailingContent = {
            TextButton(onClick = { choose = true }) { Text(stringResource(R.string.model_idle_change)) }
        }
    )
    Text(
        stringResource(R.string.model_idle_description),
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (choose) AlertDialog(
        onDismissRequest = { choose = false },
        title = { Text(stringResource(R.string.model_idle_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                ModelIdleTimeout.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = option == timeout,
                            role = Role.RadioButton,
                            onClick = { onSelected(option); choose = false }
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The whole row is one accessible radio target, avoiding
                        // duplicate announcements and a tiny label-only touch area.
                        RadioButton(selected = option == timeout, onClick = null)
                        Text(stringResource(option.label()), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { choose = false }) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
