package io.github.lrq3000.utterlane.transcribe

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.*
import java.util.UUID
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.ModelManager
import io.github.lrq3000.utterlane.settings.LocalizedActivity
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.settings.SpeakerModelNotice
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme

/** File-only entry point. No source is copied or processed until confirmation. */
open class FileTranscriptionSetupActivity : LocalizedActivity() {
    companion object {
        internal fun forPicker(context: Context, uri: Uri): Intent =
            Intent(context, FileTranscriptionSetupActivity::class.java).setAction(Intent.ACTION_PICK).setData(uri)
        internal fun forPicker(context: Context, uris: List<Uri>): Intent = forPicker(context, uris.first()).apply {
            clipData = android.content.ClipData.newRawUri("audio", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
            }
        }
        private const val RECEIPT = "import_batch_receipt"
        internal fun resume(context: Context, id: String) = Intent(context, ImportRecoveryActivity::class.java)
            .setAction("resume_import").putExtra(RECEIPT, id)
    }

    private lateinit var batchId: String
    private lateinit var session: ImportBatches.Session
    private var returnInBackground = false

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val app = UtterlaneApp.instance
        val internal = callingActivity?.packageName == packageName
        val recovering = this is ImportRecoveryActivity
        val picker = internal && (intent.action == Intent.ACTION_PICK || recovering)
        batchId = savedInstanceState?.getString(RECEIPT) ?: (if (recovering) intent.getStringExtra(RECEIPT) else null) ?: UUID.randomUUID().toString()
        returnInBackground = savedInstanceState?.getBoolean("background") == true
        val previouslyConfirmed = savedInstanceState?.getBoolean("confirmed") == true
        session = app.transcriptionQueue.imports.session(batchId)
        session.attach()
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

        fun close(openResult: Boolean) {
            if (isFinishing || isDestroyed) return
            val first = session.state.value.receipt?.firstJob
            if (openResult && first != null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                if (picker) setResult(RESULT_OK, Intent().putExtra(QueueNotifications.JOB, first))
                else startActivity(QueueNotifications.intent(this, first))
            }
            session.forget()
            finish()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { session.state.collect { state ->
                val receipt = state.receipt ?: return@collect
                if (receipt.done && receipt.accepted == receipt.total && app.transcriptionQueue.let { queue ->
                        queue.rows.value.none(queue.admission.status.value::needsResume) }) {
                    close(!returnInBackground)
                }
            } }
        }
        lifecycleScope.launch {
            session.state.collect { state -> if (returnInBackground && state.receipt?.done == true) close(false) }
        }
        setContent {
            val state by session.state.collectAsStateWithLifecycle()
            val item = state.receipt
            val submitted = item != null || state.busy
            val total = item?.total ?: uris.size
            var selecting by rememberSaveable { mutableStateOf<String?>(null) }
            val select = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val expected = selecting
                selecting = null
                if (uri != null && expected != null) session.replace(uri, expected)
            }
            fun cancel() {
                if (submitted) session.stop() else session.detachDraft()
                finish()
            }
            BackHandler { cancel() }
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
            var canNotify by remember { mutableStateOf(getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) }
            val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                canNotify = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
            }
            UtterlaneTheme(dark) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        .semantics { testTagsAsResourceId = true }.testTag("file_setup")) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.transcribe_title), style = MaterialTheme.typography.headlineSmall)
                        if ((recovering || previouslyConfirmed) && item == null) Text(stringResource(if (state.loaded) R.string.queue_batch_finished else R.string.queue_preparing_service))
                        else if (uris.isEmpty() && item == null) Text(stringResource(R.string.transcribe_error_no_audio))
                        else {
                            if (total > 1) Text(stringResource(R.string.queue_import_count, total))
                            if (!audioEnabled || audioRetention == HistoryRetention.NONE) Text(stringResource(R.string.queue_warn_audio))
                            if (!textEnabled || textRetention == HistoryRetention.NONE) Text(stringResource(R.string.queue_warn_text))
                            if (!canNotify) {
                                Text(stringResource(R.string.queue_no_notifications))
                                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                    TextButton({ askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.queue_enable_notifications)) }
                            }
                            if (item != null) Text(stringResource(R.string.queue_batch_count, item.accepted, item.total))
                            if (state.busy && item?.done != true) Text(stringResource(R.string.queue_import_progress, (item?.cursor ?: 0) + 1, total))
                            if (state.busy) TextButton({
                                // Keep this Activity's transient provider grants while it finishes staging. Only a completed private copy counts as queued.
                                if (moveTaskToBack(true)) returnInBackground = true
                            }) { Text(stringResource(R.string.queue_background)) }
                            if (item != null && !item.done && !state.busy && state.question == null) {
                                val name = Uri.parse(item.uri).lastPathSegment ?: stringResource(R.string.dialog_title)
                                Text(stringResource(when (state.problem) {
                                    ImportScreenState.Problem.DUPLICATE -> R.string.queue_batch_duplicate
                                    ImportScreenState.Problem.FILE -> R.string.queue_batch_unreadable
                                    else -> R.string.queue_batch_interrupted
                                }, item.cursor + 1, name))
                                TextButton(session::resume) { Text(stringResource(R.string.queue_batch_continue)) }
                                TextButton({ selecting = item.jobId; select.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.queue_batch_reselect)) }
                                TextButton(session::skip) { Text(stringResource(R.string.queue_batch_skip)) }
                            }
                            if (item?.done == true) {
                                val jobs by app.transcriptionQueue.rows.collectAsStateWithLifecycle()
                                val service by app.transcriptionQueue.admission.status.collectAsStateWithLifecycle()
                                if (jobs.any(service::needsResume)) QueueResumeAction(app.transcriptionQueue)
                                TextButton({ close(true) }) { Text(stringResource(if (item.firstJob != null) R.string.queue_batch_view else R.string.queue_batch_close)) }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.home_speakers), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.file_setup_speaker_help), style = MaterialTheme.typography.bodySmall)
                                }
                                val label = stringResource(R.string.home_speakers)
                                Switch(item?.speakers ?: labels, { labels = it }, enabled = !submitted, modifier = Modifier.testTag("file_setup_speakers")
                                    .semantics { contentDescription = label })
                            }
                            if ((item?.speakers ?: labels) && !available) SpeakerModelNotice()
                        }
                        if (state.problem == ImportScreenState.Problem.STORAGE) Text(stringResource(R.string.queue_batch_storage))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            if (item?.done != true) TextButton(::cancel, Modifier.testTag("file_setup_cancel")) {
                                Text(stringResource(if (state.busy) R.string.queue_batch_stop_after else if (submitted) R.string.queue_batch_stop else R.string.action_cancel))
                            }
                            if (!submitted && !recovering && !previouslyConfirmed) Button(onClick = { session.start(uris, picker, labels) },
                                enabled = uris.isNotEmpty() && state.loaded, modifier = Modifier.testTag("file_setup_start")) {
                                Text(stringResource(R.string.file_setup_transcribe))
                            }
                        }
                    }
                }
                state.question?.let { ImportRetentionQuestion(it, session::choose, session::skip, applyToBatch = total > 1) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(RECEIPT, batchId)
        outState.putBoolean("background", returnInBackground)
        outState.putBoolean("confirmed", session.state.value.let { it.busy || it.receipt != null })
        super.onSaveInstanceState(outState)
    }
}

/** Private target of immutable recovery notifications; exported share intents must not be able to select an existing receipt by ID. */
class ImportRecoveryActivity : FileTranscriptionSetupActivity()
