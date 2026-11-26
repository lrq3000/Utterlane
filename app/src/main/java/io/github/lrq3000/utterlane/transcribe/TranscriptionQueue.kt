package io.github.lrq3000.utterlane.transcribe

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.TranscriptCompletion
import io.github.lrq3000.utterlane.asr.TranscriptSource
import io.github.lrq3000.utterlane.history.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The service owns execution; screens attach to the current job or open an exact, durable completed result. */
internal class TranscriptionQueue(private val app: UtterlaneApp) {
    private val root = File(app.noBackupFilesDir, "transcription-queue")
    val resultsDirectory = File(root, "results")
    val journal = QueueJournal(root)
    private val mutable = MutableStateFlow<List<QueueRecord>>(emptyList())
    val rows = mutable.asStateFlow()
    private val reviewedIds = MutableSharedFlow<String>()
    val reviewed = reviewedIds.asSharedFlow()
    private val current = MutableStateFlow<Pair<String, TranscriptionDialogModel>?>(null)
    val active = current.asStateFlow()
    private val changed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var runner: Job? = null
    private val init = app.applicationScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        // Android may terminate the entire process. Never start another append producer on a partially written attempt.
        journal.records().filter { it.stage == QueueStage.PROCESSING || it.stage == QueueStage.DRAINING }.forEach { row ->
            app.recordingHistory.initialize()
            val audio = runCatching { app.recordingHistory.get(row.audio) }.getOrNull()
            val complete = audio?.status == "saved" && target(row.id).let { it.isFile && TranscriptSource.read(it).completion == TranscriptCompletion.COMPLETE }
            journal.update(row.copy(stage = if (complete) QueueStage.READY else QueueStage.FAILED,
                message = if (complete) null else app.getString(R.string.queue_interrupted)))
        }
        refresh()
    }
    fun target(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(resultsDirectory.apply { check(isDirectory || mkdirs() || isDirectory) }, "$id.txt")
    }
    @Synchronized private fun refresh() { mutable.value = journal.records(); changed.trySend(Unit) }
    fun visible() {
        app.applicationScope.launch {
            init.await()
            if (rows.value.any { it.stage == QueueStage.WAITING }) wake()
        }
    }
    fun wake(): Boolean = try {
        ContextCompat.startForegroundService(app, Intent(app, TranscriptionQueueService::class.java))
        true
    } catch (e: RuntimeException) {
        android.util.Log.w("TranscriptionQueue", "Processing service unavailable; pending work is preserved", e)
        false
    }

    suspend fun enqueue(source: DialogAudioActions.InspectedSource, choice: ImportRetentionChoice, options: FileTranscriptionOptions): QueueRecord {
        init.await()
        val archive = app.archival.producer(userInitiated = true)
        return try { withContext(Dispatchers.IO) {
            val owned = DialogAudioActions(app).import(source, choice, options)
            owned.lease.use {
                val row = journal.add(owned.entry.id, source.metadata.displayName ?: app.getString(R.string.dialog_title), options.speakerLabels)
                app.recordingHistory.refreshQueueHold(row.audio)
                refresh()
                archive.audio(row.audio)
                row
            }
        } } finally { archive.close() }
    }

    fun retry(audio: String, speakers: Boolean) {
        app.applicationScope.launch {
            init.await()
            try {
                withContext(Dispatchers.IO) {
                    app.recordingHistory.acquire(audio, true).use {
                        val entry = app.recordingHistory.get(audio)
                        journal.add(audio, entry.importMetadata?.displayName ?: app.getString(R.string.dialog_title), speakers)
                        app.recordingHistory.refreshQueueHold(audio); refresh()
                    }
                }
                wake()
            } catch (e: Exception) { app.homeController.showMessage(e.message ?: app.getString(R.string.transcribe_error_failed)) }
        }
    }

    suspend fun captureEnded(id: String, audio: String, label: String) {
        init.await()
        withContext(Dispatchers.IO) {
            val row = journal.add(audio, label, false, QueueStage.DRAINING, id)
            app.recordingHistory.refreshQueueHold(audio); refresh()
            QueueNotifications(app).post(row)
        }
    }
    suspend fun completeCapture(id: String, failure: String?) {
        init.await()
        withContext(Dispatchers.IO) {
            val row = journal.find(id) ?: return@withContext
            if (row.stage != QueueStage.DRAINING) return@withContext
            journal.update(row.copy(stage = if (failure == null) QueueStage.READY else QueueStage.FAILED, message = failure))
            refresh(); QueueNotifications(app).post(journal.find(id)!!)
        }
    }
    suspend fun acknowledge(id: String) = withContext(Dispatchers.IO) {
        val row = journal.find(id) ?: return@withContext
        if (journal.acknowledge(id)) {
            app.recordingHistory.refreshQueueHold(row.audio)
            app.transcriptHistory.refreshQueueHold(row.textId)
            refresh(); QueueNotifications(app).remove(row)
            reviewedIds.emit(id)
        }
    }
    suspend fun input(id: String): DialogInput? = withContext(Dispatchers.IO) {
        init.await()
        val row = journal.find(id)?.takeIf { it.stage.reviewable } ?: return@withContext null
        val audio = runCatching { app.recordingHistory.get(row.audio) }.getOrNull()
        val saved = app.transcriptHistory.find(row.textId)
        val file = target(row.id).takeIf { it.isFile && !TranscriptSource.read(it).discarded }
        DialogInput(audioId = row.audio, transcriptId = saved?.id, transcriptPath = file?.absolutePath,
            transcriptOrigin = false, metadata = saved?.let(::TranscriptMetadata) ?: audio?.let(::TranscriptMetadata),
            fileOptions = FileTranscriptionOptions(row.speakers), recovered = row.stage == QueueStage.FAILED, queueId = id)
    }
    fun run(serviceScope: CoroutineScope, idle: () -> Unit) {
        if (runner?.isActive == true) { changed.trySend(Unit); return }
        runner = serviceScope.launch {
            init.await()
            while (isActive) {
                val row = rows.value.firstOrNull { it.stage == QueueStage.WAITING }
                if (row != null) { process(row); continue }
                if (rows.value.none { it.stage == QueueStage.DRAINING }) break
                changed.receive()
            }
            idle()
        }
    }
    private suspend fun process(row: QueueRecord) {
        val notices = QueueNotifications(app)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        var message: String? = null
        var terminal = QueueStage.FAILED
        try {
            withContext(Dispatchers.IO) { journal.update(row.copy(stage = QueueStage.PROCESSING)); refresh() }
            val model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = TranscriptionDialogModel(app,
                    DialogInput(audioId = row.audio, automatic = true, fileOptions = FileTranscriptionOptions(row.speakers), queueId = row.id)) as T
            })[TranscriptionDialogModel::class.java]
            current.value = row.id to model
            coroutineScope {
                val progress = launch {
                    model.state.map { it.fileProgress?.percent }.distinctUntilChanged().collect {
                        notices.post(row.copy(stage = QueueStage.PROCESSING), it)
                    }
                }
                try { model.awaitOperation() } finally { progress.cancel() }
            }
            val end = model.state.value
            terminal = if (end.fileProgress?.stage == FileProgressStage.COMPLETE) QueueStage.READY else QueueStage.FAILED
            message = end.message
        } catch (e: CancellationException) { message = app.getString(R.string.queue_interrupted); throw e }
        catch (e: Exception) { message = e.message ?: app.getString(R.string.transcribe_error_failed) }
        finally {
            current.value = null
            owner.viewModelStore.clear()
            withContext(NonCancellable + Dispatchers.IO) {
                val finished = row.copy(stage = terminal, message = message)
                journal.update(finished); refresh(); notices.post(finished)
            }
        }
    }
}
