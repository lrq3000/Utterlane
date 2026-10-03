package io.github.lrq3000.utterlane.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.ModelCatalog
import kotlinx.coroutines.launch

@Composable
fun ModelSelector() {
    val app = UtterlaneApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected by app.modelManager.selected.collectAsStateWithLifecycle()
    var choose by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(stringResource(R.string.model_choose)) },
        supportingContent = { Text(selected.name) },
        trailingContent = { TextButton(onClick = { choose = true }) { Text(stringResource(R.string.history_change)) } })
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text(stringResource(R.string.model_choose)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ModelCatalog.models.forEach { model ->
                    TextButton(onClick = {
                        scope.launch {
                            try { app.recognizerManager.selectModel(model); choose = false }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() }
                        }
                    }) {
                        Column {
                            Text((if (model == selected) "✓ " else "") + model.name)
                            Text("${model.downloadBytes / 1000000} MB · " + context.getString(if (app.modelManager.isModelReady(model)) R.string.model_downloaded else R.string.model_not_downloaded))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choose = false }) { Text(stringResource(R.string.overlay_cancel)) } })
}
