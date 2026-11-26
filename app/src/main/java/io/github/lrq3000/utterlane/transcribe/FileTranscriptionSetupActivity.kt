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
import androidx.lifecycle.lifecycleScope
import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.ModelManager
import io.github.lrq3000.utterlane.settings.LocalizedActivity
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.settings.SpeakerModelNotice
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme

/** File-only entry point. No source is copied or processed until confirmation. */
class FileTranscriptionSetupActivity : LocalizedActivity() {
    companion object {
        internal fun forPicker(context: Context, uri: Uri): Intent =
            Intent(context, FileTranscriptionSetupActivity::class.java).setAction(Intent.ACTION_PICK).setData(uri)
        internal fun forPicker(context: Context, uris: List<Uri>): Intent = forPicker(context, uris.first()).apply {
            clipData = android.content.ClipData.newRawUri("audio", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
            }
        }
    }

    private var submitted by mutableStateOf(false)
    private var index by mutableIntStateOf(0)
    private var question by mutableStateOf<ImportRetentionRequest?>(null)
    private var answer: CompletableDeferred<Pair<ImportRetentionChoice, Boolean>?>? = null

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val app = UtterlaneApp.instance
        val picker = intent.action == Intent.ACTION_PICK && callingActivity?.packageName == packageName
        val clips = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        val uris = runCatching {
            when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: clips.firstOrNull())
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().ifEmpty { clips }
                Intent.ACTION_VIEW -> listOfNotNull(intent.data)
                TranscribeActivity.ACTION_TRANSCRIBE ->
                    listOfNotNull(IntentCompat.getParcelableExtra(intent, TranscribeActivity.EXTRA_AUDIO_URI, Uri::class.java) ?: intent.data)
                Intent.ACTION_PICK -> if (picker) clips.ifEmpty { listOfNotNull(intent.data) } else emptyList()
                else -> emptyList()
            }
        }.getOrDefault(emptyList()).distinct().filter { it.scheme == "content" || (it.scheme == "file" &&
            it.path?.let { p -> java.io.File(p).canonicalFile.toPath().startsWith(android.os.Environment.getExternalStorageDirectory().canonicalFile.toPath()) } == true) }

        setContent {
            val theme by app.settingsRepository.themeMode.collectAsStateWithLifecycle(SettingsRepository.THEME_SYSTEM)
            val dark = when (theme) {
                SettingsRepository.THEME_DARK -> true
                SettingsRepository.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            val count by app.settingsRepository.speakerCount.collectAsStateWithLifecycle(0)
            val modelState by app.diarizationModels.downloadState.collectAsStateWithLifecycle()
            val available = count == 1 || modelState == ModelManager.DownloadState.Ready
            var labels by rememberSaveable { mutableStateOf(FileTranscriptionOptions().speakerLabels) }
            val audioEnabled by app.settingsRepository.audioHistoryEnabled.collectAsStateWithLifecycle(true)
            val textEnabled by app.settingsRepository.transcriptHistoryEnabled.collectAsStateWithLifecycle(true)
            val audioRetention by app.settingsRepository.audioHistoryRetention.collectAsStateWithLifecycle(HistoryRetention.NONE)
            val textRetention by app.settingsRepository.transcriptHistoryRetention.collectAsStateWithLifecycle(HistoryRetention.NONE)
            UtterlaneTheme(dark) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        .semantics { testTagsAsResourceId = true }.testTag("file_setup")) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.transcribe_title), style = MaterialTheme.typography.headlineSmall)
                        if (uris.isEmpty()) Text(stringResource(R.string.transcribe_error_no_audio))
                        else {
                            if (uris.size > 1) Text(stringResource(R.string.queue_import_count, uris.size))
                            if (!audioEnabled || audioRetention == HistoryRetention.NONE) Text(stringResource(R.string.queue_warn_audio))
                            if (!textEnabled || textRetention == HistoryRetention.NONE) Text(stringResource(R.string.queue_warn_text))
                            if (submitted) Text(stringResource(R.string.queue_import_progress, index + 1, uris.size))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.home_speakers), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.file_setup_speaker_help), style = MaterialTheme.typography.bodySmall)
                                }
                                val label = stringResource(R.string.home_speakers)
                                Switch(labels, { labels = it }, enabled = !submitted, modifier = Modifier.testTag("file_setup_speakers")
                                    .semantics { contentDescription = label })
                            }
                            if (labels && !available) SpeakerModelNotice()
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(::finish, Modifier.testTag("file_setup_cancel")) { Text(stringResource(R.string.action_cancel)) }
                            Button(onClick = { confirm(uris, picker, FileTranscriptionOptions(labels)) },
                                enabled = uris.isNotEmpty() && !submitted, modifier = Modifier.testTag("file_setup_start")) {
                                Text(stringResource(R.string.file_setup_transcribe))
                            }
                        }
                    }
                }
                question?.let { ImportRetentionQuestion(it, { choice, remember -> answer?.complete(choice to remember) }, { answer?.complete(null) }) }
            }
        }
    }

    private fun confirm(uris: List<Uri>, picker: Boolean, options: FileTranscriptionOptions) {
        if (submitted) return
        submitted = true
        val app = application as UtterlaneApp
        lifecycleScope.launch {
            val preferences = app.settingsRepository.importRetentionPreferences.first()
            val batch = mutableMapOf<ImportRetentionCategory, ImportRetentionChoice>()
            var first: String? = null
            var failures = 0
            for ((position, uri) in uris.withIndex()) {
                index = position
                try {
                    val source = withContext(Dispatchers.IO) { DialogAudioActions(app).inspect(uri, null) }
                    val decision = ImportRetentionPolicy.decide(source.metadata.durationMs, source.metadata.sourceBytes, preferences)
                    val choice = when (decision) {
                        is ImportRetentionDecision.Use -> decision.choice
                        is ImportRetentionDecision.Ask -> batch[decision.category] ?: run {
                            val pending = CompletableDeferred<Pair<ImportRetentionChoice, Boolean>?>()
                            answer = pending; question = ImportRetentionRequest(decision.category, source.metadata)
                            val result = try { pending.await() } finally { question = null; answer = null }
                            if (result != null) {
                                batch[decision.category] = result.first
                                if (result.second) app.settingsRepository.rememberImportRetention(decision.category, result.first)
                            }
                            result?.first
                        }
                    } ?: continue
                    val row = app.transcriptionQueue.enqueue(source, choice, options)
                    if (first == null) first = row.id
                    app.transcriptionQueue.wake()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failures++; android.util.Log.w("TranscriptionQueue", "File import failed", e) }
            }
            if (failures > 0) android.widget.Toast.makeText(this@FileTranscriptionSetupActivity,
                getString(R.string.queue_failed_count, failures), android.widget.Toast.LENGTH_LONG).show()
            if (picker) setResult(RESULT_OK, Intent().putExtra(QueueNotifications.JOB, first))
            else if (first != null) startActivity(QueueNotifications.intent(this@FileTranscriptionSetupActivity, first))
            finish()
        }
    }
}
