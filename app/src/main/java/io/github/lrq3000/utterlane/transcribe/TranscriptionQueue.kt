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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The service owns execution; screens attach to the current job or open an exact, durable completed result. */
internal class TranscriptionQueue(private val app: UtterlaneApp) {
    private val root = File(app.noBackupFilesDir, "transcription-queue")
    val resultsDirectory = File(root, "results")
    val journal = QueueJournal(root)
    private val retries by lazy { QueueRetryRegistration(app.recordingHistory, journal) }
    val terminalDelivery = QueueTerminalDelivery(journal)
    val batchStore = ImportBatchStore(root)
    val imports = ImportBatches(app, batchStore)
    val admission = QueueServiceAdmission()
    private val staging = MutableStateFlow(0)
    private val standalone = LinkedHashMap<Job, () -> Unit>()
    private val standaloneCount = MutableStateFlow(0)
    val standaloneActive = standaloneCount.asStateFlow()
    @Volatile var isQuitting = false
        private set
    fun protectsAudio(id: String) = synchronized(journal) { journal.protectsAudio(id) || batchStore.protectsAudio(id) }
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
    // The dispatcher and this handle live on Main. Journal transitions arbitrate between UI commands, dispatch, producer finalization and cold-start recovery.
    private var executing: Pair<String, Job>? = null
    private val commands = Mutex()
    private val restarts = QueueRestartRequests()
    private val disposition = QueueDisposition()
    /** Serialize the last-owner check through acknowledgement, including across different rows that refer to the same recording. */
    suspend fun <T> withDisposition(action: suspend () -> T): T = disposition.run(action)
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
    fun ownsWorkingText(file: File): Boolean =
        file.parentFile == resultsDirectory && journal.find(file.name.removeSuffix(".source").removeSuffix(".txt")) != null

    fun recoverableUnclaimedText() = TranscriptSource.recoverableCopies(resultsDirectory).filterNot { ownsWorkingText(it.file) }

    fun pruneUnclaimedText(maximumAgeMs: Long) {
        io.github.lrq3000.utterlane.asr.TranscriptStore.prune(resultsDirectory, maximumAgeMs) { !ownsWorkingText(it) }
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
    fun wake(): Boolean = !isQuitting && admission.request {
        checkNotNull(ContextCompat.startForegroundService(app, Intent(app, TranscriptionQueueService::class.java)))
    }.also { if (!it) serviceRefused() }
    fun serviceRefused() {
        admission.refused()
        updateWaitingNotice()
    }
    private fun updateWaitingNotice() {
        if (admission.status.value != QueueServiceAdmission.Status.STOPPED) return
        val notices = ImportRecoveryNotice(app)
        val pending = rows.value.firstOrNull { it.stage == QueueStage.WAITING }
        if (pending == null) notices.clearWaiting() else notices.waiting(pending)
    }
    /** Call on Main at the visible submission/resume click, before copying. The runner must not exit in the empty interval between two accepted files. */
    fun beginStaging(): Boolean {
        if (isQuitting) return false
        staging.update { it + 1 }
        wake()
        return true
    }
    fun endStaging() { staging.update { it - 1 }; changed.trySend(Unit) }

    /** Account for registrations/finalizers launched outside the producing microphone or file Job. */
    fun <T : Job> trackPublication(job: T): T {
        staging.update { it + 1 }
        job.invokeOnCompletion { endStaging() }
        return job
    }

    fun trackStandalone(job: Job, stop: () -> Unit) {
        standalone[job] = stop
        standaloneCount.value = standalone.size
        trackPublication(job).invokeOnCompletion {
            app.applicationScope.launch { standalone.remove(job); standaloneCount.value = standalone.size }
        }
        if (isQuitting) stop()
    }

    fun beginQuit() {
        isQuitting = true
        journal.beginQuit()
        changed.trySend(Unit)
    }

    suspend fun pauseFilesForQuit() {
        init.await()
        commands.withLock {
            withContext(Dispatchers.IO) {
                journal.pauseFilesForQuit().filter { it.stage == QueueStage.PAUSED }.forEach(terminalNotices::remove)
                refresh()
                updateWaitingNotice()
            }
            standalone.values.toList().forEach { it() }
            current.value?.second?.interruptFile()
            executing?.second?.join()
        }
    }

    suspend fun awaitQuitIdle() {
        combine(staging, rows, foreground) { publishing, all, foreground ->
            publishing == 0 && foreground == null && all.none { it.stage.needsProcessingService || it.stage == QueueStage.CAPTURING }
        }.first { it }
        imports.requireRecoverableForQuit()
    }

    fun endQuit() {
        journal.endQuit()
        isQuitting = false
        changed.trySend(Unit)
    }

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
        if (isQuitting) return
        trackPublication(app.applicationScope.launch {
            init.await()
            try {
                val job = withContext(Dispatchers.IO) {
                    registerRetry(audio, speakers, modelId).also { refresh() }
                }
                wake()
                if (!isQuitting) accepted(job.id)
            } catch (e: Exception) { app.homeController.showMessage(e.message ?: app.getString(R.string.transcribe_error_failed)) }
        })
    }

    private fun registerRetry(audio: String, speakers: Boolean, modelId: String?, previous: String? = null): QueueRecord =
        retries.add(audio, speakers, modelId, app.getString(R.string.dialog_title), previous)

    private fun command(withDisposition: Boolean = false, action: suspend () -> Unit): Job =
        app.applicationScope.launch {
            try {
                init.await()
                if (isQuitting) return@launch
                if (withDisposition) disposition.run { commands.withLock { action() } }
                else commands.withLock { action() }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.homeController.showMessage(e.message ?: app.getString(R.string.transcribe_error_failed)) }
        }

    fun pause(id: String) = command {
        val next = withContext(Dispatchers.IO) {
            journal.transition(id, setOf(QueueStage.WAITING, QueueStage.PROCESSING)) {
                it.copy(stage = if (it.stage == QueueStage.WAITING) QueueStage.PAUSED else QueueStage.PAUSING)
            }?.also { refresh() }
        }
        if (next?.stage == QueueStage.PAUSING) current.value?.takeIf { it.first == id }?.second?.interruptFile()
        if (next?.stage == QueueStage.PAUSED) updateWaitingNotice()
    }

    // Confirmed deletion holds disposition through join, tombstoning and acknowledgement. Continue must not requeue the same row between those steps.
    fun resume(id: String) = command(withDisposition = true) {
        val resumed = withContext(Dispatchers.IO) {
            journal.continueJob(id)?.also { terminalNotices.remove(it); refresh() }
        }
        if (resumed != null) wake()
    }

    fun stop(id: String) = command { stopFile(id) }

    fun restart(id: String, open: (String) -> Unit) {
        if (!restarts.claim(id)) return
        command {
            val before = rows.value.firstOrNull { it.id == id } ?: return@command
            if (before.stage !in setOf(QueueStage.WAITING, QueueStage.PROCESSING, QueueStage.PAUSED, QueueStage.STOPPED)) return@command
            if (!stopFile(id)) return@command
            val next = withContext(Dispatchers.IO) {
                registerRetry(before.audio, before.speakers, journal.find(id)?.modelId ?: before.modelId, previous = id).also { refresh() }
            }
            wake(); open(next.id)
        }.invokeOnCompletion { restarts.release(id) }
    }

    suspend fun stopForDeletion(id: String): Boolean = withContext(Dispatchers.Main.immediate) {
        init.await()
        commands.withLock { stopFile(id, forDeletion = true) }
    }

    private suspend fun stopFile(id: String, forDeletion: Boolean = false): Boolean {
        val row = withContext(Dispatchers.IO) {
            journal.transition(id, setOf(QueueStage.WAITING, QueueStage.PROCESSING, QueueStage.PAUSING, QueueStage.PAUSED)) {
                // Stop takes the row out of the active queue. A parked or waiting row has no producer to interrupt, so its existing checkpoint is already safe.
                if (it.stage == QueueStage.WAITING || it.stage == QueueStage.PAUSED)
                    it.copy(stage = QueueStage.STOPPED, checkpointed = !forDeletion || it.checkpointed)
                else it.copy(stage = QueueStage.STOPPING)
            }?.also { refresh() } ?: journal.find(id)
        } ?: return false
        if (row.stage.reviewable) {
            withContext(Dispatchers.IO) { deliverTerminal(id); updateWaitingNotice() }
            return true
        }
        if (row.stage != QueueStage.STOPPING) return false
        executing?.takeIf { it.first == id }?.second?.let { producer ->
            if (forDeletion) producer.cancelAndJoin()
            else {
                current.value?.takeIf { it.first == id }?.second?.interruptFile()
                producer.join()
            }
        }
        return withContext(Dispatchers.IO) {
            journal.transition(id, setOf(QueueStage.STOPPING)) { it.copy(stage = QueueStage.STOPPED, message = null) }
            refresh(); deliverTerminal(id); updateWaitingNotice()
            journal.find(id)?.stage?.reviewable == true
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
            QueueNotifications(app).publish(row)
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
            updateWaitingNotice()
            reviewedIds.emit(id)
        }
    }
    suspend fun input(id: String): DialogInput? = withContext(Dispatchers.IO) {
        init.await()
        val row = journal.find(id)?.takeIf { it.stage.reviewable || it.stage == QueueStage.PAUSED || it.stage == QueueStage.WAITING } ?: return@withContext null
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
                val snapshot = rows.value
                val row = if (isQuitting) null else nextEligibleFile(snapshot, app.modelManager.selected.value.id)
                if (row != null) {
                    val job = launch(start = CoroutineStart.LAZY) { process(row) }
                    executing = row.id to job
                    try { job.start(); job.join() } finally { executing = null }
                    continue
                }
                if (staging.value == 0 && snapshot.none {
                    if (isQuitting) it.stage.needsProcessingService || it.stage == QueueStage.CAPTURING
                    else it.stage == QueueStage.DRAINING || it.stage == QueueStage.WAITING
                }) break
                changed.receive()
            }
            idle()
        }
    }
    private suspend fun process(selection: QueueRecord) {
        var row = selection
        val notices = QueueNotifications(app)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        var message: String? = null
        var terminal = QueueStage.FAILED
        var checkpoint: Long? = null
        var usedModel = row.modelId
        var worker: TranscriptionDialogModel? = null
        var claimed = false
        try {
            withContext(Dispatchers.IO) {
                journal.transition(row.id, setOf(QueueStage.WAITING)) { it.copy(stage = QueueStage.PROCESSING) }?.let {
                    row = it; claimed = true; refresh()
                }
            }
            if (!claimed) return
            val model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = TranscriptionDialogModel(app,
                    DialogInput(audioId = row.audio, automatic = true, fileOptions = FileTranscriptionOptions(row.speakers), queueId = row.id,
                        modelId = row.modelId)) as T
            })[TranscriptionDialogModel::class.java]
            worker = model
            model.awaitOperation()
            val stageBeforeDecode = rows.value.firstOrNull { it.id == row.id }?.stage
            if (stageBeforeDecode == QueueStage.PAUSING || stageBeforeDecode == QueueStage.STOPPING) {
                checkpoint = row.resumeSamples
                terminal = QueueStage.PAUSED
                return
            }
            model.retry(resumeAt = row.resumeSamples.takeIf { row.checkpointed }, speakerSection = row.speakerSection)
            current.value = row.id to model
            coroutineScope {
                val progress = launch {
                    model.state.map { it.fileProgress?.percent }.distinctUntilChanged().collect {
                        val stage = rows.value.firstOrNull { r -> r.id == row.id }?.stage
                        if (stage == QueueStage.PROCESSING) notices.post(row.copy(stage = stage), it)
                    }
                }
                try { model.awaitOperation() } finally { progress.cancel() }
            }
            val end = model.state.value
            val request = rows.value.firstOrNull { it.id == row.id }?.stage
            checkpoint = model.pausedAt ?: row.resumeSamples.takeIf {
                request in setOf(QueueStage.PAUSING, QueueStage.STOPPING) && end.fileProgress == null && end.store == null
            }
            usedModel = end.store?.source?.modelId ?: usedModel
            // Once the complete marker wins the race, the result is durable. Do not offer Continue against an already-complete transcript.
            terminal = when {
                checkpoint != null -> QueueStage.PAUSED
                end.completion == TranscriptCompletion.COMPLETE -> QueueStage.READY
                else -> QueueStage.FAILED
            }
            message = end.message
        } catch (e: CancellationException) { message = app.getString(R.string.queue_interrupted); throw e }
        catch (e: Exception) { message = e.message ?: app.getString(R.string.transcribe_error_failed) }
        finally {
            withContext(NonCancellable) {
                worker?.cancelOperation(manualStop = rows.value.any { it.id == row.id && it.stage == QueueStage.STOPPING })
                current.value = null
                owner.viewModelStore.clear()
                if (claimed) withContext(Dispatchers.IO) {
                    val finished = journal.transition(row.id, setOf(QueueStage.PROCESSING, QueueStage.PAUSING, QueueStage.STOPPING)) {
                        val stopped = it.stage == QueueStage.STOPPING
                        it.copy(stage = if (stopped && terminal != QueueStage.READY) QueueStage.STOPPED else terminal,
                            message = message.takeUnless { stopped }, resumeSamples = checkpoint ?: 0L,
                            checkpointed = checkpoint != null, modelId = usedModel)
                    }
                     if (finished?.stage == QueueStage.PAUSED) synchronized(journal) {
                         // A separate screen can continue the job before the producer's final notification. Never overwrite the new stage with a stale pause notice.
                          if (journal.find(row.id) == finished) {
                              if (isQuitting) terminalNotices.remove(finished) else terminalNotices.publish(finished)
                          }
                     } else deliverTerminal(row.id)
                     refresh()
                }
            }
        }
    }
}

/** Bypass only incompatible model switches while a capture holds its model. Other waiting files keep FIFO order; a pause is never eligible. */
internal fun nextEligibleFile(rows: List<QueueRecord>, currentModel: String): QueueRecord? {
    val capture = rows.any { it.stage == QueueStage.CAPTURING || it.stage == QueueStage.DRAINING }
    return rows.firstOrNull { it.stage == QueueStage.WAITING && (!capture || it.modelId == null || it.modelId == currentModel) }
}

internal fun QueueRecord.reviewInput(saved: TranscriptEntry?, file: File?, entry: HistoryEntry?) = DialogInput(audioId = audio,
    transcriptId = saved?.id, transcriptPath = file?.absolutePath, transcriptOrigin = false, modelId = modelId,
    metadata = saved?.let(::TranscriptMetadata) ?: entry?.let(::TranscriptMetadata), fileOptions = FileTranscriptionOptions(speakers),
    recovered = stage == QueueStage.FAILED, queueId = id, pausedSamples = resumeSamples.takeIf { checkpointed && canContinue },
    unfinishedQueueView = !stage.reviewable || stage == QueueStage.STOPPED && canContinue)

/** A teardown before Stop can suppress handoff after a preview already started registration. Complete that exact row after its writer exits; do not start a new producer. */
internal suspend fun afterCapturePublication(registering: Deferred<Unit>?, handoff: Deferred<Boolean>?, complete: suspend () -> Unit) {
    if (handoff == null && registering == null) return
    if (handoff?.await() != true) registering?.await()
    complete()
}
