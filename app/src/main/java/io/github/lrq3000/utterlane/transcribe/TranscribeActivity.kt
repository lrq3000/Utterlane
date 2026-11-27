package io.github.lrq3000.utterlane.transcribe

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.lifecycle.lifecycleScope
import io.github.lrq3000.utterlane.R
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.history.TranscriptMetadata
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme

/** All audio entry points share a retained operation owner and the same dialog actions. */
class TranscribeActivity : io.github.lrq3000.utterlane.settings.LocalizedActivity() {
    companion object {
        const val ACTION_TRANSCRIBE = "io.github.lrq3000.utterlane.action.TRANSCRIBE"
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_AUDIO_ID = "history_id"
        const val EXTRA_TRANSCRIPT_ID = "transcript_id"
        const val EXTRA_RECOVERY_ID = "recording_recovery_id"
    }
    private lateinit var model: TranscriptionDialogModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the platform's dialog dimming, but make its content area available
        // to the nearly full-height reader instead of the theme's 85% minimum.
        window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
        val app = UtterlaneApp.instance
        app.historyCleanup.userEntry(intent, savedInstanceState)
        setFinishOnTouchOutside(false)
        intent.getStringExtra(QueueNotifications.JOB)?.let { id ->
            showQueueJob(app, id)
            return
        }
        val audio = savedInstanceState?.getString("owned_audio") ?: intent.getStringExtra(EXTRA_AUDIO_ID) ?: intent.getStringExtra(EXTRA_RECOVERY_ID)
        val textId = if (savedInstanceState?.containsKey("saved_text") == true) savedInstanceState.getString("saved_text") else intent.getStringExtra(EXTRA_TRANSCRIPT_ID)
        val request = DialogInput(extractAudioUri(intent), intent.getStringExtra(EXTRA_FILE_PATH), audio, textId,
            savedInstanceState?.getString("working_text") ?: intent.getStringExtra("transcript_path"),
            automatic = savedInstanceState == null && audio == null && textId == null && !intent.hasExtra("transcript_path"),
            transcriptOrigin = savedInstanceState?.getBoolean("text_origin") ?: intent.hasExtra(EXTRA_TRANSCRIPT_ID),
            modelName = savedInstanceState?.getString("result_model").orEmpty(), modelId = savedInstanceState?.getString("result_model_id"),
            metadata = TranscriptMetadata.fromBundle(savedInstanceState),
            recovered = intent.hasExtra(EXTRA_RECOVERY_ID) || intent.hasExtra("transcript_path"),
            fileOptions = FileTranscriptionOptions.from(intent))
        if (request.automatic && app.transcriptionQueue.isQuitting) { finish(); return }
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TranscriptionDialogModel(app, request) as T
        })[TranscriptionDialogModel::class.java]
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { model.requestExit() }
        })
        setContent {
            val theme by app.settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = SettingsRepository.THEME_SYSTEM)
            val dark = when (theme) { SettingsRepository.THEME_DARK -> true; SettingsRepository.THEME_LIGHT -> false; else -> isSystemInDarkTheme() }
            UtterlaneTheme(darkTheme = dark) {
                TranscriptionDialog(model, onClose = model::requestExit, onEmpty = { finish() })
            }
        }
    }

    private fun showQueueJob(app: UtterlaneApp, id: String) {
        val queue = app.transcriptionQueue
        queue.visible()
        lifecycleScope.launch { queue.reviewed.collect { reviewed -> if (reviewed == id) finish() } }
        setContent {
            val rows by queue.rows.collectAsStateWithLifecycle()
            val loaded by queue.loaded.collectAsStateWithLifecycle()
            val active by queue.active.collectAsStateWithLifecycle()
            val record = rows.firstOrNull { it.id == id }
            val stage = record?.stage
            val stopped = stage == QueueStage.STOPPED
            var confirmingDelete by rememberSaveable(id) { mutableStateOf(false) }
            val showSnapshot = stage?.reviewable == true || stage == QueueStage.PAUSED || stage == QueueStage.WAITING
            val snapshotOwner = remember(id, stage, record?.resumeSamples) {
                object : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = androidx.lifecycle.ViewModelStore() }
            }
            DisposableEffect(snapshotOwner) { onDispose { snapshotOwner.viewModelStore.clear() } }
            var review by remember(id, stage, record?.resumeSamples) { mutableStateOf<TranscriptionDialogModel?>(null) }
            LaunchedEffect(id, stage, record?.resumeSamples) {
                val input = if (showSnapshot) queue.input(id) else null
                if (input != null) review = ViewModelProvider(if (stage?.reviewable == true && !stopped) this@TranscribeActivity else snapshotOwner, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TranscriptionDialogModel(app, input) as T
                })["review-$id-$stage", TranscriptionDialogModel::class.java]
            }
            val theme by app.settingsRepository.themeMode.collectAsStateWithLifecycle(SettingsRepository.THEME_SYSTEM)
            UtterlaneTheme(darkTheme = when (theme) { SettingsRepository.THEME_DARK -> true; SettingsRepository.THEME_LIGHT -> false; else -> isSystemInDarkTheme() }) {
                val reader = review
                val processing = active?.takeIf { it.first == id }?.second
                val hide = { if (!moveTaskToBack(true)) finish(); Unit }
                val controls = stage?.takeIf { it in setOf(QueueStage.WAITING, QueueStage.PAUSING, QueueStage.PAUSED, QueueStage.PROCESSING,
                    QueueStage.STOPPING, QueueStage.STOPPED) }?.let {
                    QueueJobControls(it, { queue.pause(id) }, { queue.resume(id) }, { queue.stop(id) },
                        { queue.restart(id) { next -> startActivity(QueueNotifications.intent(this, next)); finish() } }, { confirmingDelete = true },
                        canContinue = record?.canContinue == true)
                }
                if (processing != null && stage?.reviewable == false) {
                    BackHandler { finish() }
                    TranscriptionDialog(processing, onClose = ::finish, onEmpty = ::finish, onBackground = hide, jobControls = controls)
                } else if (reader != null) {
                    val state by reader.state.collectAsStateWithLifecycle()
                    val close = { if (stage?.reviewable != true || stopped) finish() else if (!state.importing) reader.requestExit(); Unit }
                    BackHandler(onBack = close)
                    Column {
                        record?.message?.let { Text(it, Modifier.padding(8.dp)) }
                        val service by queue.admission.status.collectAsStateWithLifecycle()
                        if (record != null && service.needsResume(record)) QueueResumeAction(queue)
                        TranscriptionDialog(reader, close, onEmpty = ::finish, onBackground = hide.takeUnless { stage?.reviewable == true }, jobControls = controls)
                    }
                } else Surface {
                    Column(Modifier.padding(20.dp)) {
                        record?.let { Text(it.label) }
                        val service by queue.admission.status.collectAsStateWithLifecycle()
                        if (record != null && service.needsResume(record)) QueueResumeAction(queue)
                        Text(stringResource(record?.stage?.label() ?: if (loaded) R.string.queue_missing else R.string.state_processing))
                        IconButton(hide, Modifier.testTag("dialog_background")) {
                            Icon(androidx.compose.material.icons.Icons.Outlined.PictureInPictureAlt, stringResource(R.string.queue_background))
                        }
                    }
                }
                // Keep the confirmation outside the stage-dependent reader: joining a producer can change the stage and require a second identity check.
                if (confirmingDelete) QueueCancelDialog(id) { confirmingDelete = false }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::model.isInitialized) model.saveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun extractAudioUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> parcelUri(intent, Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> intent.data
        ACTION_TRANSCRIBE -> parcelUri(intent, EXTRA_AUDIO_URI) ?: intent.data
        else -> null
    }
    @Suppress("DEPRECATION") private fun parcelUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Uri::class.java) else intent.getParcelableExtra(key)

    override fun onDestroy() {
        super.onDestroy()
        // Activity destruction is not explicit dismissal. The ViewModel releases
        // its runtime owners while disk-backed recovery survives unexpected loss.
        if (!intent.hasExtra(QueueNotifications.JOB)) getSystemService(NotificationManager::class.java).cancel(AudioMonitorService.AUDIO_DETECTED_NOTIFICATION_ID)
    }
}
