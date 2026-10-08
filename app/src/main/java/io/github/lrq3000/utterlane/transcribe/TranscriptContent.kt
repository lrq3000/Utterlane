package io.github.lrq3000.utterlane.transcribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.TranscriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only, scrollable presentation of a bounded preview or page, never the whole store.
 * Callers own paging and empty-state content; modifier can constrain Home's card height.
 * The default 300dp text limit also keeps this usable inside the dialog's scrolling column.
 */
@Composable
fun TranscriptText(preview: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()) {
        Text(preview, Modifier.padding(12.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
    }
}

/**
 * Full-store transfers shared by Home and the detail dialog. Copy is byte-bounded;
 * Share uses the existing file snapshot/export path for arbitrarily long results.
 * additionalActions puts caller-owned More/Keep text controls in the same wrapping row;
 * enabled gates only Copy/Share, so the caller controls its other actions independently.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptTransferActions(
    store: TranscriptStore?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    additionalActions: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            store?.let { source -> scope.launch {
                // A visible page can be only the last 8KB: never use it as the
                // clipboard source, and never read the backing file on Main.
                val text = withContext(Dispatchers.IO) { source.readForTransfer() }
                if (text != null) (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Transcript", text))
                else Toast.makeText(context, R.string.stream_use_export, Toast.LENGTH_LONG).show()
            } }
        }, enabled = enabled && store != null) { Text(stringResource(R.string.transcribe_copy)) }
        FilledTonalButton(onClick = { store?.let { shareTranscript(context, it) } }, enabled = enabled && store != null) {
            Text(stringResource(R.string.dialog_share_text))
        }
        additionalActions()
    }
}
