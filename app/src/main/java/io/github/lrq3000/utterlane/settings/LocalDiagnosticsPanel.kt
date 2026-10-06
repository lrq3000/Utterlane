package io.github.lrq3000.utterlane.settings

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit export only; neither enabling diagnostics nor opening this panel shares anything. */
@Composable
fun LocalDiagnosticsPanel(enabled: Boolean) {
    val context = LocalContext.current
    val diagnostics = remember { UtterlaneApp.instance.recognitionDiagnostics }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    fun action(share: Boolean) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                if (share) {
                    // Export is a queue barrier on the same writer as appends/rotation.
                    // FileProvider receives only the newly created immutable ZIP.
                    val snapshot = diagnostics.snapshot()
                    val uri = withContext(Dispatchers.IO) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", snapshot)
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(context.getString(R.string.diagnostics_title), uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.diagnostics_share)))
                } else {
                    diagnostics.clear()
                    message = R.string.diagnostics_cleared
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { message = R.string.diagnostics_action_failed
            } finally { busy = false }
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(if (enabled) R.string.diagnostics_enabled else R.string.diagnostics_disabled), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.diagnostics_privacy), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = !busy, onClick = { action(true) }) { Text(stringResource(R.string.diagnostics_share)) }
            TextButton(enabled = !busy, onClick = { action(false) }) { Text(stringResource(R.string.diagnostics_clear)) }
        }
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
    }
}
