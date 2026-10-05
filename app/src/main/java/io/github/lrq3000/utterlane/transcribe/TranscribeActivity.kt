package io.github.lrq3000.utterlane.transcribe

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.SettingsRepository
import io.github.lrq3000.utterlane.ui.theme.UtterlaneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import io.github.lrq3000.utterlane.asr.TranscriptStore
import androidx.core.content.FileProvider

class TranscribeActivity : io.github.lrq3000.utterlane.settings.LocalizedActivity() {

    companion object {
        const val ACTION_TRANSCRIBE = "io.github.lrq3000.utterlane.action.TRANSCRIBE"
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_FILE_PATH = "file_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioUri = extractAudioUri(intent)
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)

        setContent {
            val settingsRepository = UtterlaneApp.instance.settingsRepository
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.THEME_SYSTEM
            )

            val isDarkTheme = when (themeMode) {
                SettingsRepository.THEME_DARK -> true
                SettingsRepository.THEME_LIGHT -> false
                else -> null
            }

            UtterlaneTheme(
                darkTheme = isDarkTheme ?: isSystemInDarkTheme()
            ) {
                TranscribeScreen(
                    audioUri = audioUri,
                    filePath = filePath,
                    historyId = intent.getStringExtra("history_id"),
                    transcriptPath = intent.getStringExtra("transcript_path"),
                    onDismiss = { finish() },
                    onCopy = { text -> copyToClipboard(text) }
                )
            }
        }
    }

    private fun extractAudioUri(intent: Intent): Uri? {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }
            Intent.ACTION_VIEW -> {
                intent.data
            }
            ACTION_TRANSCRIBE -> {
                getParcelableExtraCompat(intent, EXTRA_AUDIO_URI, Uri::class.java)
                    ?: intent.data
            }
            else -> null
        }
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun <T> getParcelableExtraCompat(intent: Intent, name: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, clazz)
        } else {
            intent.getParcelableExtra(name) as? T
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dismiss the "audio detected" notification when activity closes
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(AudioMonitorService.AUDIO_DETECTED_NOTIFICATION_ID)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(
    audioUri: Uri?,
    filePath: String? = null,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    historyId: String? = null,
    transcriptPath: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf<TranscriptStore?>(null) }
    var preview by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Int?>(null) }
    var running by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var pageOffset by remember { mutableStateOf<Long?>(null) }
    var processingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var preserveResult by remember { mutableStateOf(false) }
    val captureMetrics = remember { io.github.lrq3000.utterlane.asr.CaptureMetrics() }
    val capture by captureMetrics.state.collectAsState()
    val manager = remember { UtterlaneApp.instance.recognizerManager }
    LaunchedEffect(manager, running) {
        if (running && transcriptPath == null) manager.activity.collect {
            if (it.active || captureMetrics.state.value.recognition.active) captureMetrics.recognition(it)
        }
    }

    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, running) {
        val previous = view.keepScreenOn
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = previous }
    }

    DisposableEffect(store) {
        val result = store
        val ownerJob = processingJob
        onDispose {
            if (result != null) UtterlaneApp.instance.applicationScope.launch {
                // Wait for a cancelled native call to leave the session before
                // disposing its file. Outstanding export leases also defer deletion.
                ownerJob?.join()
                if (preserveResult && result.file.length() > 0) io.github.lrq3000.utterlane.service.TranscriptRecovery.show(context, result)
                else withContext(Dispatchers.IO) { result.dispose() }
            }
        }
    }

    // Decoding and inference interleave. Only a bounded tail enters Compose state.
    LaunchedEffect(audioUri, filePath, historyId, transcriptPath) {
        processingJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        var activeSession: io.github.lrq3000.utterlane.asr.TranscriptionSession? = null
        var power: io.github.lrq3000.utterlane.asr.TranscriptionPower? = null
        var captureDiagnostics: io.github.lrq3000.utterlane.diagnostics.CaptureDiagnosticSession? = null
        var diagnosticObserver: kotlinx.coroutines.Job? = null
        try {
            if (transcriptPath != null) {
                val recovered = withContext(Dispatchers.IO) {
                    val file = java.io.File(transcriptPath).canonicalFile
                    require(file.parentFile == java.io.File(context.cacheDir, "transcripts").canonicalFile && file.isFile) { "Transcript is unavailable" }
                    TranscriptStore(file)
                }
                store = recovered
                preview = withContext(Dispatchers.IO) { recovered.page((recovered.file.length() - 8000).coerceAtLeast(0)) }
                return@LaunchedEffect
            }
            require(audioUri != null || filePath != null || historyId != null) { context.getString(R.string.transcribe_error_no_audio) }
            power = io.github.lrq3000.utterlane.asr.TranscriptionPower(context)
            withContext(Dispatchers.IO) {
                val app = UtterlaneApp.instance
                val options = app.settingsRepository.runtimeOptions.first()
                val diagnosticSession = app.recognitionDiagnostics.capture(options)
                captureDiagnostics = diagnosticSession
                // Owned by the screen operation, not the decoder coroutine (which must
                // be free to return before this indefinitely collecting child is stopped).
                diagnosticObserver = scope.launch { captureMetrics.state.collect { diagnosticSession.record(it) } }
                check(app.modelManager.isModelReady()) { context.getString(R.string.transcribe_error_no_model) }
                var session: io.github.lrq3000.utterlane.asr.TranscriptionSession? = null
                session = app.recognizerManager.createSession(options, onProcessed = captureMetrics::processed) {
                    val tail = session!!.store.preview()
                    withContext(Dispatchers.Main) { if (pageOffset == null) preview = tail }
                }
                activeSession = session
                withContext(Dispatchers.Main) { store = session.store }
                val decoder = AudioDecoder(app)
                // Decoder completion is not transcript finalization (speaker/ASR tail may remain).
                val onProgress: (Int?) -> Unit = { value -> scope.launch { progress = value?.coerceIn(0, 99) } }
                val accept: suspend (ShortArray) -> Unit = { samples ->
                    captureMetrics.captured(samples.size)
                    session.accept(samples)
                }
                if (historyId != null) {
                    app.recordingHistory.acquire(historyId).use {
                        val entry = app.recordingHistory.get(historyId)
                        var offset = 0L
                        while (offset < entry.samples) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val samples = app.recordingHistory.read(historyId, offset, 3200)
                            accept(samples)
                            offset += samples.size
                            onProgress((offset * 100 / entry.samples).toInt())
                        }
                        session.finish()
                    }
                } else if (filePath != null) decoder.decode(filePath, accept, onProgress)
                else decoder.decode(audioUri!!, accept, onProgress)
                captureMetrics.captureEnded()
                session.finish()
                captureMetrics.completed(null)
                withContext(Dispatchers.Main) { progress = 100 }
                if (session.store.segments == 0) withContext(Dispatchers.Main) { message = context.getString(R.string.toast_no_speech) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            captureMetrics.cancelled()
            preserveResult = true
            message = context.getString(R.string.stream_cancelled)
            throw e
        } catch (e: Exception) {
            captureMetrics.completed(context.getString(R.string.recognition_error))
            preserveResult = true
            android.util.Log.e("TranscribeActivity", "Incremental transcription failed", e)
            message = e.message ?: context.getString(R.string.transcribe_error_failed)
        } finally {
            diagnosticObserver?.cancel()
            captureDiagnostics?.record(captureMetrics.state.value)
            try {
                activeSession?.close()
                // Cancellation can happen between file creation and the first UI
                // publication. Such an unexposed empty store still has an owner.
                if (store == null) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { activeSession?.store?.dispose() }
            } finally { running = false; power?.close() }
        }
    }

    // The floating Activity already dims the host window. A second Compose scrim
    // would draw a dark rectangular strip inside that window, around the card.
    Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Box(contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).padding(vertical = 24.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.transcribe_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.overlay_cancel)) }
                    }
                    if (running) {
                        Text(if (progress == null) stringResource(R.string.transcribe_transcribing) else stringResource(R.string.transcribe_decoding, progress!!))
                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { progress!! / 100f }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { processingJob?.cancel() }) { Text(stringResource(R.string.overlay_cancel)) }
                    }
                    if (transcriptPath == null) {
                        Text(io.github.lrq3000.utterlane.ui.RecognitionStatusText.activity(context, capture.recognition), style = MaterialTheme.typography.bodySmall)
                        Text(io.github.lrq3000.utterlane.ui.RecognitionStatusText.backlog(context, capture), style = MaterialTheme.typography.bodySmall)
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (preview.isNotEmpty()) {
                        SuccessContent(preview, onCopy = {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) { store?.readForTransfer() }
                                if (text != null) { onCopy(text); message = context.getString(R.string.transcribe_copied) }
                                else message = context.getString(R.string.stream_use_export)
                            }
                        }, onShare = { store?.let { shareTranscript(context, it) } })
                        Text(stringResource(R.string.stream_preview), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    val current = store ?: return@launch
                                    val offset = ((pageOffset ?: current.file.length()) - 7980).coerceAtLeast(0)
                                    val text = withContext(Dispatchers.IO) { current.page(offset) }
                                    pageOffset = offset; preview = text
                                }
                            }) { Text(stringResource(R.string.stream_previous)) }
                            TextButton(onClick = {
                                scope.launch {
                                    val current = store ?: return@launch
                                    val offset = (pageOffset ?: 0) + 7980
                                    if (offset >= current.file.length()) { pageOffset = null; preview = current.preview() }
                                    else { preview = withContext(Dispatchers.IO) { current.page(offset) }; pageOffset = offset }
                                }
                            }) { Text(stringResource(R.string.stream_next)) }
                        }
                    }
                }
            }
        }
    }
}

fun shareTranscript(context: Context, store: TranscriptStore) {
    val lease = store.acquire()
    UtterlaneApp.instance.applicationScope.launch {
        try {
            // Snapshot only completed text. Sharing never races subsequent appends
            // or forces the native inference worker to wait for a disk copy.
            val file = withContext(Dispatchers.IO) { store.snapshot() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Transcript", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.transcribe_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            android.util.Log.e("TranscribeActivity", "Transcript export failed", e)
            android.widget.Toast.makeText(context, e.message ?: context.getString(R.string.transcribe_error_failed), android.widget.Toast.LENGTH_LONG).show()
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { lease.close() } }
    }
}

@Composable
fun LoadingContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SuccessContent(
    text: String,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Transcription result
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 300.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            Button(
                onClick = onCopy,
                shape = MaterialTheme.shapes.small
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.transcribe_copy))
            }

            FilledTonalButton(onClick = onShare, shape = MaterialTheme.shapes.small) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.transcribe_share))
            }
        }
    }
}

@Composable
fun ErrorContent(
    message: String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onDismiss, shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.transcribe_close))
        }
    }
}
