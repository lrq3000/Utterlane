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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
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
import io.github.lrq3000.utterlane.history.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.util.UUID
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.ModelManager
import io.github.lrq3000.utterlane.settings.LocalizedActivity
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.settings.SpeakerModelNotice
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme

internal enum class ImportSetupHandoff { STAY, OPEN, BACKGROUND }

/** Closing releases an external Activity's transient grants. Wait for every private copy and all setup decisions, including service promotion. */
internal fun ImportScreenState.handoff(background: Boolean, service: QueueServiceAdmission.Status, jobs: List<QueueRecord>): ImportSetupHandoff {
    val item = receipt
    if (busy || question != null || problem != null || item == null || !item.done || item.firstJob == null || item.accepted != item.total)
        return ImportSetupHandoff.STAY
    val first = jobs.firstOrNull { it.id == item.firstJob } ?: return ImportSetupHandoff.STAY
    if (background && first.stage == QueueStage.FAILED) return ImportSetupHandoff.STAY
    if (service != QueueServiceAdmission.Status.RUNNING && jobs.any { it.stage == QueueStage.WAITING }) return ImportSetupHandoff.STAY
    return if (background) ImportSetupHandoff.BACKGROUND else ImportSetupHandoff.OPEN
}

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
    private var returnInBackground by mutableStateOf(false)

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
            if (openResult && first != null) {
                // The previous combine emission can outlive RESUMED. Retry on the next resume instead of discarding the receipt without opening its result.
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
                if (picker) setResult(RESULT_OK, Intent().putExtra(QueueNotifications.JOB, first))
                else startActivity(QueueNotifications.intent(this, first))
            }
            session.forget()
            finish()
        }
        lifecycleScope.launch {
            val queue = app.transcriptionQueue
            combine(session.state, queue.admission.status, queue.rows, lifecycle.currentStateFlow) { state, service, jobs, lifecycleState ->
                state.handoff(returnInBackground, service, jobs) to lifecycleState
            }.collect { (handoff, lifecycleState) ->
                when (handoff) {
                    ImportSetupHandoff.OPEN -> if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) close(true)
                    ImportSetupHandoff.BACKGROUND -> close(false)
                    ImportSetupHandoff.STAY -> Unit
                }
            }
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
                    FileSetupViewport {
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
                                if (returnInBackground) jobs.firstOrNull { it.id == item.firstJob && it.stage == QueueStage.FAILED }?.let { failed ->
                                    Text(failed.message ?: stringResource(R.string.queue_failed), color = MaterialTheme.colorScheme.error)
                                }
                                TextButton({ close(true) }) { Text(stringResource(if (item.firstJob != null) R.string.queue_batch_view else R.string.queue_batch_close)) }
                            }
                            FileSetupOption(R.string.home_speakers, R.string.file_setup_speaker_help, "file_setup_speakers",
                                item?.speakers ?: labels, !submitted) { labels = it }
                            FileSetupOption(R.string.import_background_switch, R.string.import_background_help, "file_setup_background",
                                returnInBackground, !submitted) { returnInBackground = it }
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

/** Wrap to the viewport normally. Keep a minimum readable form width so oversized text and very narrow windows scroll rather than squeezing the switch rows. */
@Composable
private fun FileSetupViewport(content: @Composable ColumnScope.() -> Unit) {
    val x = rememberScrollState()
    val y = rememberScrollState()
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    BoxWithConstraints(Modifier.fillMaxWidth().drawWithContent {
        drawContent()
        fun thumb(state: ScrollState, length: Float): Pair<Float, Float>? {
            if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE || length <= 0) return null
            val visible = (length * length / (length + state.maxValue)).coerceIn(minOf(22.dp.toPx(), length), length)
            return (length - visible) * (state.value.toFloat() / state.maxValue) to visible
        }
        val inset = 5.dp.toPx()
        val endX = if (rtl) inset else size.width - inset
        thumb(y, size.height - inset * 2)?.let { (start, span) ->
            drawLine(color, Offset(endX, inset + start), Offset(endX, inset + start + span), 3.dp.toPx(), StrokeCap.Round)
        }
        val length = size.width - inset * 2
        thumb(x, length)?.let { (start, span) ->
            val left = inset + if (rtl) length - span - start else start
            drawLine(color, Offset(left, size.height - inset), Offset(left + span, size.height - inset), 3.dp.toPx(), StrokeCap.Round)
        }
    }) {
        val contentWidth = maxOf(maxWidth, 240.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f))
        Box(Modifier.testTag("file_setup_scroll_x").horizontalScroll(x)) {
            Column(Modifier.width(contentWidth).testTag("file_setup_scroll_y").verticalScroll(y).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun FileSetupOption(title: Int, help: Int, tag: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(title)
    val control: @Composable () -> Unit = {
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag).semantics { contentDescription = label })
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            control()
        }
        Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Private target of immutable recovery notifications; exported share intents must not be able to select an existing receipt by ID. */
class ImportRecoveryActivity : FileTranscriptionSetupActivity()
