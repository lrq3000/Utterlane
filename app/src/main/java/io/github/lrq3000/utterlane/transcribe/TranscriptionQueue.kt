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
    val terminalDelivery = QueueTerminalDelivery(journal)
    val batchStore = ImportBatchStore(root)
    val imports = ImportBatches(app, batchStore)
    val admission = QueueServiceAdmission()
    private var staging = 0
    fun protectsAudio(id: String) = journal.protectsAudio(id) || batchStore.protectsAudio(id)
    private val mutable = MutableStateFlow<List<QueueRecord>>(emptyList())
    val rows = mutable.asStateFlow()
    private val hydrated = MutableStateFlow(false)
    val loaded = hydrated.asStateFlow()
    private val serviceOwner = MutableStateFlow<TranscriptionQueueService?>(null)
    val foreground = serviceOwner.asStateFlow()
    fun attached(service: TranscriptionQueueService) { serviceOwner.value = service }
    fun detached(service: TranscriptionQueueService) { serviceOwner.compareAndSet(service, null) }
    private val reviewedIds = MutableSharedFlow<String>()
    val reviewed = reviewedIds.asSharedFlow()
    private val current = MutableStateFlow<Pair<String, TranscriptionDialogModel>?>(null)
    val active = current.asStateFlow()
    private val changed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var runner: Job? = null
    private val startupCheckpoint = io.github.lrq3000.utterlane.home.HomeJournal(app).restore()
    private val init = app.applicationScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        batchStore.reconcile(journal)
        imports.refresh()
        QueueRecovery(journal, app.recordingHistory, ::target).restore(startupCheckpoint,
            app.getString(R.string.dialog_title), app.getString(R.string.queue_interrupted))
        val pending = journal.records().filter { it.stage.reviewable && !it.terminalPosted }
        pending.forEach { deliverTerminal(it.id) }
        refresh()
        hydrated.value = true
    }
    fun target(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(resultsDirectory.apply { check(isDirectory || mkdirs() || isDirectory) }, "$id.txt")
    }
    private val terminalNotices by lazy { QueueNotifications(app) }
    private fun deliverTerminal(id: String) {
        try { terminalDelivery.deliver(id) { terminalNotices.publish(it) } }
        catch (e: Exception) { android.util.Log.w("TranscriptionQueue", "Terminal delivery is pending; result is still reviewable", e) }
    }
    @Synchronized private fun refresh() { mutable.value = journal.records(); changed.trySend(Unit) }
    fun visible() {
        init.start()
    }
    suspend fun initialized() { init.await() }
    fun wake(): Boolean = admission.request {
        checkNotNull(ContextCompat.startForegroundService(app, Intent(app, TranscriptionQueueService::class.java)))
    }.also { if (!it) serviceRefused() }
    fun serviceRefused() {
        admission.refused()
        rows.value.firstOrNull { it.stage == QueueStage.WAITING }?.let { ImportRecoveryNotice(app).waiting(it) }
    }
    /** Call on Main at the visible submission/resume click, before copying. The runner must not exit in the empty interval between two accepted files. */
    fun beginStaging() { staging++; wake() }
    fun endStaging() { staging--; changed.trySend(Unit) }

    suspend fun resumeStaged(item: ImportReceipt): Boolean {
        journal.find(item.jobId)?.let { batchStore.advance(item, accepted = true); return true }
        val staged = app.recordingHistory.stagedImport(item.jobId) ?: return false
        staged.lease.use { publishImport(item, staged.entry) }
        return true
    }
    suspend fun enqueue(item: ImportReceipt, source: DialogAudioActions.InspectedSource, choice: ImportRetentionChoice) {
        val owned = try { DialogAudioActions(app).import(source, choice, FileTranscriptionOptions(item.speakers), item.jobId) }
        catch (e: Exception) {
            // A provider may fail to close after the completed copy was published. Adopt it before deciding the source has failed.
            if (resumeStaged(item)) return
            throw ImportSourceFailure(e)
        }
        owned.lease.use { publishImport(item, owned.entry) }
    }
    private suspend fun publishImport(item: ImportReceipt, entry: HistoryEntry) {
        val archive = app.archival.producer()
        try {
            val row = synchronized(journal) {
                journal.add(entry.id, entry.importMetadata?.displayName ?: app.getString(R.string.dialog_title), item.speakers, id = item.jobId)
                    .also { batchStore.advance(item, accepted = true) }
            }
            app.recordingHistory.refreshQueueHold(row.audio)
            refresh()
            if (admission.status.value.needsResume(row)) ImportRecoveryNotice(app).waiting(row)
            archive.audio(row.audio)
        } finally { archive.close() }
    }

    fun retry(audio: String, speakers: Boolean, modelId: String? = null, accepted: (String) -> Unit = {}) {
        app.applicationScope.launch {
            init.await()
            try {
                val job = withContext(Dispatchers.IO) {
                    app.recordingHistory.acquire(audio, true).use {
                        val entry = app.recordingHistory.get(audio)
                        val row = journal.add(audio, entry.importMetadata?.displayName ?: app.getString(R.string.dialog_title), speakers,
                            modelId = modelId)
                        app.recordingHistory.refreshQueueHold(audio); refresh()
                        row
                    }
                }
                wake()
                accepted(job.id)
            } catch (e: Exception) { app.homeController.showMessage(e.message ?: app.getString(R.string.transcribe_error_failed)) }
        }
    }

    suspend fun captureStarted(id: String, audio: String, label: String, speakers: Boolean) {
        init.await()
        withContext(Dispatchers.IO) {
            if (journal.find(id) == null) journal.add(audio, label, speakers, QueueStage.CAPTURING, id)
            app.recordingHistory.refreshQueueHold(audio); refresh()
        }
    }
    suspend fun captureEnded(id: String, audio: String, label: String, speakers: Boolean) {
        init.await()
        withContext(Dispatchers.IO) {
            val current = journal.find(id)
            val row = if (current == null) journal.add(audio, label, speakers, QueueStage.DRAINING, id)
                else current.copy(stage = QueueStage.DRAINING, speakers = speakers).also(journal::update)
            app.recordingHistory.refreshQueueHold(audio); refresh()
            QueueNotifications(app).post(row)
        }
    }
    suspend fun completeCapture(id: String, failure: String?, speakers: Boolean?) {
        init.await()
        withContext(Dispatchers.IO) {
            journal.finishCapture(id, failure, app.getString(R.string.queue_interrupted), speakers) ?: return@withContext
            refresh(); deliverTerminal(id)
        }
    }
    suspend fun acknowledge(id: String) = withContext(Dispatchers.IO) {
        val row = journal.find(id) ?: return@withContext
        if (row.stage.reviewable) io.github.lrq3000.utterlane.home.HomeJournal(app).clearIfQueue(id)
        if (terminalDelivery.acknowledge(id, QueueNotifications(app)::remove)) {
            app.recordingHistory.refreshQueueHold(row.audio)
            app.transcriptHistory.refreshQueueHold(row.textId)
            refresh()
            reviewedIds.emit(id)
        }
    }
    suspend fun input(id: String): DialogInput? = withContext(Dispatchers.IO) {
        init.await()
        val row = journal.find(id)?.takeIf { it.stage.reviewable } ?: return@withContext null
        val audio = runCatching { app.recordingHistory.get(row.audio) }.getOrNull()
        val saved = app.transcriptHistory.find(row.textId)
        val file = target(row.id).takeIf { it.isFile && !TranscriptSource.read(it).discarded }
        row.reviewInput(saved, file, audio)
    }
    fun run(serviceScope: CoroutineScope, idle: () -> Unit) {
        if (runner?.isActive == true) { changed.trySend(Unit); return }
        runner = serviceScope.launch {
            init.await()
            while (isActive) {
                val row = rows.value.firstOrNull { it.stage == QueueStage.WAITING }
                if (row != null) {
                    // An explicitly requested model switch cannot evict a live or draining capture. Keep its place and wake on capture completion rather than failing the queued retry.
                    if (row.modelId != null && row.modelId != app.modelManager.selected.value.id &&
                        rows.value.any { it.stage == QueueStage.CAPTURING || it.stage == QueueStage.DRAINING }) {
                        changed.receive()
                        continue
                    }
                    process(row); continue
                }
                if (staging == 0 && rows.value.none { it.stage == QueueStage.DRAINING }) break
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
                    DialogInput(audioId = row.audio, automatic = true, fileOptions = FileTranscriptionOptions(row.speakers), queueId = row.id,
                        modelId = row.modelId)) as T
            })[TranscriptionDialogModel::class.java]
            model.awaitOperation()
            model.retry()
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
                journal.update(finished); refresh(); deliverTerminal(row.id)
            }
        }
    }
}

internal fun QueueRecord.reviewInput(saved: TranscriptEntry?, file: File?, entry: HistoryEntry?) = DialogInput(audioId = audio,
    transcriptId = saved?.id, transcriptPath = file?.absolutePath, transcriptOrigin = false, modelId = modelId,
    metadata = saved?.let(::TranscriptMetadata) ?: entry?.let(::TranscriptMetadata), fileOptions = FileTranscriptionOptions(speakers),
    recovered = stage == QueueStage.FAILED, queueId = id)

/** A teardown before Stop can suppress handoff after a preview already started registration. Complete that exact row after its writer exits; do not start a new producer. */
internal suspend fun afterCapturePublication(registering: Deferred<Unit>?, handoff: Deferred<Boolean>?, complete: suspend () -> Unit) {
    if (handoff == null && registering == null) return
    if (handoff?.await() != true) registering?.await()
    complete()
}
