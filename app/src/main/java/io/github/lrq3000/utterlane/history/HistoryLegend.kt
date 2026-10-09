package io.github.lrq3000.utterlane.history

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp

/** Shared by embedded and standalone histories; policy follows the displayed
 * kind, not the linked source or a hard-coded retention default. */
@Composable
internal fun HistoryLegend(transcripts: Boolean) {
    val settings = UtterlaneApp.instance.settingsRepository
    val duration by (if (transcripts) settings.transcriptHistoryRetention else settings.audioHistoryRetention)
        .collectAsStateWithLifecycle(initialValue = HistoryRetention.DEFAULT)
    val automatic by (if (transcripts) settings.transcriptHistoryEnabled else settings.audioHistoryEnabled)
        .collectAsStateWithLifecycle(initialValue = true)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("history_legend"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem(if (transcripts) Icons.Default.Description else Icons.Outlined.GraphicEq,
            stringResource(if (transcripts) R.string.history_legend_transcript else R.string.history_legend_audio))
        LegendItem(Icons.Default.Restore, stringResource(R.string.history_recovered_entry))
        LegendItem(Icons.Default.PushPin, stringResource(R.string.history_pinned))
        Text(stringResource(when (duration) {
            HistoryRetention.NONE -> R.string.history_legend_immediate
            HistoryRetention.FOREVER -> R.string.history_legend_forever
            else -> R.string.history_legend_retention
        }, stringResource(duration.label())), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!automatic) Text(stringResource(R.string.history_legend_disabled),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
