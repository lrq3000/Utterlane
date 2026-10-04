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
import io.github.lrq3000.utterlane.asr.ModelBackend
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import androidx.compose.ui.unit.dp

@Composable
fun ModelSelector() {
    val app = UtterlaneApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected by app.modelManager.selected.collectAsStateWithLifecycle()
    val custom by app.modelManager.customModels.collectAsStateWithLifecycle()
    var choose by remember { mutableStateOf(false) }
    var imports by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var primary by remember { mutableStateOf<Uri?>(null) }
    var importing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { primary = null; imports = it }
    fun import(primaryUri: Uri, codec: Uri? = null) {
        val files = imports
        imports = emptyList(); primary = null; importing = true; choose = false; failure = null
        // The application scope owns the transfer so a locale/activity change
        // does not strand a partial bundle. ModelManager exposes cancellation.
        app.applicationScope.launch {
            try {
                val model = app.modelManager.importCustom(files, primaryUri, codec)
                app.recognizerManager.selectModel(model)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failure = e.message }
            finally { importing = false }
        }
    }
    ListItem(headlineContent = { Text(stringResource(R.string.model_choose)) },
        supportingContent = { Text(selected.name) },
        trailingContent = { TextButton(onClick = { choose = true }) { Text(stringResource(R.string.history_change)) } })
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text(stringResource(R.string.model_choose)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (ModelCatalog.models + custom).forEach { model ->
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
                            if (model.backend == ModelBackend.TRANSCRIBE_CPP) Text(stringResource(R.string.model_native_ternary_description))
                        }
                    }
                }
                TextButton(enabled = !importing, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.model_custom))
                }
            }
        }, confirmButton = { TextButton(onClick = { choose = false }) { Text(stringResource(R.string.overlay_cancel)) } })
    if (imports.isNotEmpty()) AlertDialog(onDismissRequest = { imports = emptyList(); primary = null },
        title = { Text(stringResource(if (primary == null) R.string.model_custom_primary else R.string.model_custom_codec)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(if (primary == null) R.string.model_custom_help else R.string.model_custom_codec_help))
            imports.filter { it != primary }.forEach { uri -> TextButton(onClick = {
                val chosen = primary
                if (chosen != null) import(chosen, uri)
                else if (imports.size == 1) import(uri)
                else primary = uri
            }) {
                Text(DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty())
            } }
        } }, confirmButton = {
            primary?.let { chosen -> TextButton(onClick = { import(chosen) }) { Text(stringResource(R.string.model_custom_codec_auto)) } }
        }, dismissButton = { TextButton(onClick = { imports = emptyList(); primary = null }) { Text(stringResource(R.string.action_cancel)) } })
    if (importing) Row(Modifier.padding(horizontal = 16.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp))
        TextButton(onClick = { app.modelManager.cancelTransfer() }) { Text(stringResource(R.string.action_cancel)) }
    }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
}
