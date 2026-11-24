package io.github.lrq3000.utterlane.transcribe

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.LocalizedActivity
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme

/** File-only entry point. No source is copied or processed until confirmation. */
class FileTranscriptionSetupActivity : LocalizedActivity() {
    companion object {
        internal fun forPicker(context: Context, uri: Uri): Intent =
            Intent(context, FileTranscriptionSetupActivity::class.java).setAction(Intent.ACTION_PICK).setData(uri)
    }

    private var submitted = false

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val app = UtterlaneApp.instance
        val picker = intent.action == Intent.ACTION_PICK && callingActivity?.packageName == packageName
        val clip = intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
        val uri = runCatching {
            when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: clip
                Intent.ACTION_VIEW -> intent.data
                TranscribeActivity.ACTION_TRANSCRIBE ->
                    IntentCompat.getParcelableExtra(intent, TranscribeActivity.EXTRA_AUDIO_URI, Uri::class.java) ?: intent.data
                Intent.ACTION_PICK -> intent.data.takeIf { picker }
                else -> null
            }
        }.getOrNull()?.takeIf { it.scheme == "content" || it.scheme == "file" }

        setContent {
            val theme by app.settingsRepository.themeMode.collectAsStateWithLifecycle(SettingsRepository.THEME_SYSTEM)
            val dark = when (theme) {
                SettingsRepository.THEME_DARK -> true
                SettingsRepository.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            val count by app.settingsRepository.speakerCount.collectAsStateWithLifecycle(0)
            val modelState by app.diarizationModels.downloadState.collectAsStateWithLifecycle()
            val available = remember(modelState, count) { count == 1 || app.diarizationModels.isModelReady() }
            var labels by rememberSaveable { mutableStateOf(FileTranscriptionOptions().speakerLabels) }
            UtterlaneTheme(dark) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        .semantics { testTagsAsResourceId = true }.testTag("file_setup")) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.transcribe_title), style = MaterialTheme.typography.headlineSmall)
                        if (uri == null) Text(stringResource(R.string.transcribe_error_no_audio))
                        else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.home_speakers), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.file_setup_speaker_help), style = MaterialTheme.typography.bodySmall)
                                }
                                val label = stringResource(R.string.home_speakers)
                                Switch(labels, { labels = it }, modifier = Modifier.testTag("file_setup_speakers")
                                    .semantics { contentDescription = label })
                            }
                            if (labels && !available) Text(stringResource(R.string.file_speakers_unavailable),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(::finish, Modifier.testTag("file_setup_cancel")) { Text(stringResource(R.string.action_cancel)) }
                            Button(onClick = { uri?.let { confirm(it, picker, FileTranscriptionOptions(labels)) } },
                                enabled = uri != null, modifier = Modifier.testTag("file_setup_start")) {
                                Text(stringResource(R.string.file_setup_transcribe))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun confirm(uri: Uri, picker: Boolean, options: FileTranscriptionOptions) {
        if (submitted) return
        submitted = true
        if (picker) {
            // Home already holds the document grant from its picker Activity result.
            setResult(RESULT_OK, options.putInto(Intent().setData(uri)))
        } else {
            val approved = Intent(this, TranscribeActivity::class.java).setAction(TranscribeActivity.ACTION_TRANSCRIBE)
            if (uri.scheme == "content") approved.setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            else approved.putExtra(TranscribeActivity.EXTRA_FILE_PATH, uri.path)
            try { startActivity(options.putInto(approved)) }
            catch (error: SecurityException) {
                // A provider can withdraw a temporary read grant while setup is open.
                android.widget.Toast.makeText(this, R.string.transcribe_error_no_audio, android.widget.Toast.LENGTH_LONG).show()
                submitted = false
                return
            }
        }
        finish()
    }
}
