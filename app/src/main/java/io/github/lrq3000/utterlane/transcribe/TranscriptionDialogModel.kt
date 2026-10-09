package io.github.lrq3000.utterlane.transcribe

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.settings.VisualRefreshRate
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DialogInput(val uri: Uri? = null, val path: String? = null, val audioId: String? = null,
    val transcriptId: String? = null, val transcriptPath: String? = null, val automatic: Boolean = false,
    val transcriptOrigin: Boolean = transcriptId != null, val modelName: String = "", val modelId: String? = null)

data class TranscriptionDialogState(
    val audio: HistoryEntry? = null, val store: TranscriptStore? = null, val transcriptId: String? = null,
    val preview: String = "", val transcriptBytes: Long = 0, val transcriptPinned: Boolean = false, val running: Boolean = false,
    val importing: Boolean = false, val saving: Boolean = false, val closing: Boolean = false,
    val message: String? = null, val model: String = "",
    val fileProgress: FileProgressSnapshot? = null,
    val capture: CaptureSnapshot = CaptureSnapshot(), val visualRate: Int = VisualRefreshRate.DEFAULT,
    val deletion: DialogDeletionRequest? = null, val checkingDeletion: Boolean = false,
    val deleting: Boolean = false, val finished: Boolean = false
)

data class DialogDeletionRequest(val plan: HistoryDeletionPlan,
    val target: HistoryDeletionTarget? = plan.choices.singleOrNull())

enum class DialogPinTarget { AUDIO, TRANSCRIPT, BOTH }

/**
 * One retained owner for sharing, history and recovery. Android recreation does
 * not acknowledge/discard a session. Only explicit dismiss/delete does that.
 */
class TranscriptionDialogModel(private val app: UtterlaneApp, val input: DialogInput) : ViewModel() {
    val playbackOwner: String = java.util.UUID.randomUUID().toString()
    private val mutable = MutableStateFlow(TranscriptionDialogState(importing = true))
    val state: StateFlow<TranscriptionDialogState> = mutable
    private val audioActions = DialogAudioActions(app)
    private val linkedHistory = LinkedHistory(app.recordingHistory, app.transcriptHistory)
    val document = TranscriptPager(viewModelScope)
    private var operation: Job? = null
    private var saving: Job? = null
    @Volatile private var ownedAudioId = input.audioId
    private val temporaryResults = linkedSetOf<File>()
    @Volatile private var currentStore: TranscriptStore? = null
    @Volatile private var latestPreview = ""
    @Volatile private var fileProgress = FileTranscriptionProgress()
    private var chosenModel = input.modelName
    private var resultModelId: String? = input.modelId
    private var lastRequestedModelId: String? = input.modelId
    var metrics = CaptureMetrics()
        private set

    init {
        viewModelScope.launch { app.settingsRepository.visualRefreshRate.collect { rate ->
            metrics.setVisualRefreshRate(rate); mutable.update { it.copy(visualRate = rate) }
        } }
        viewModelScope.launch(Dispatchers.IO) { app.recordingHistory.revision.collect { refreshAudio() } }
        viewModelScope.launch(Dispatchers.IO) { app.transcriptHistory.revision.collect { refreshTranscript() } }
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val entry = input.transcriptId?.let(app.transcriptHistory::find)
                        ?: if (input.transcriptId == null && input.transcriptPath == null && !input.automatic)
                            latestLinkedTranscript() else null
                    if (entry != null) {
                        ownedAudioId = ownedAudioId ?: entry.audioId
                        chosenModel = entry.model; resultModelId = entry.modelId; lastRequestedModelId = entry.modelId
                        mutable.update { it.copy(transcriptId = entry.id, model = entry.model) }
                    }
                    val restored = input.transcriptPath?.let { File(it).canonicalFile }?.takeIf { file ->
                        require(file.parentFile == File(app.cacheDir, "transcripts").canonicalFile) { "Transcript is unavailable" }
                        file.isFile
                    }
                    if (restored != null) exposeStore(TranscriptStore(restored))
                    else if (entry != null) {
                        app.transcriptHistory.acquire(entry.id).use {
                            val directory = File(app.cacheDir, "transcripts").apply { mkdirs() }
                            val copy = File.createTempFile("view-", ".txt", directory)
                            entry.file.copyTo(copy, overwrite = true)
                            // Publish the owner on IO before returning across the
                            // cancellable Main-dispatch boundary; dismissal joins it.
                            val store = TranscriptStore(copy)
                            store.attachSource(TranscriptSource(entry.audioId, entry.id, entry.model, entry.modelId))
                            exposeStore(store)
                        }
                    } else if (input.transcriptId != null || input.transcriptPath != null) error("Transcript is unavailable")
                    else if (!input.automatic) {
                        // History can be disabled or a recognition attempt can fail
                        // before autosaving. Its durable working copy is still linked.
                        linkedWorkingCopies().maxByOrNull { it.file.lastModified() }?.let { exposeStore(TranscriptStore(it.file)) }
                    }
                }
                if (ownedAudioId == null && (input.uri != null || input.path != null)) {
                    mutable.update { it.copy(importing = true) }
                    runInterruptible(Dispatchers.IO) {
                        // Publish ownership before dispatch back to Main, including
                        // when cancellation races completion of the source copy.
                        ownedAudioId = audioActions.import(input.uri, input.path).id
                    }
                }
                withContext(Dispatchers.IO) { refreshAudio() }
                mutable.update { it.copy(importing = false) }
                if (input.automatic && ownedAudioId != null) transcribe()
                else if (mutable.value.audio?.needsRecovery == true) mutable.update {
                    it.copy(message = if (it.audio?.failureKind == "MODEL") app.getString(R.string.dialog_model_failed)
                        else it.audio?.failureMessage ?: app.getString(R.string.dialog_recovery_info))
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { showError(e)
            } finally { mutable.update { it.copy(importing = false) } }
        }
    }

    private fun refreshAudio() {
        val audio = linkedHistory.availableAudio(ownedAudioId)
        mutable.update { it.copy(audio = audio) }
    }

    private fun refreshTranscript() {
        val entry = app.transcriptHistory.find(mutable.value.transcriptId)
        mutable.update { it.copy(transcriptPinned = entry?.retention?.pinned == true) }
    }

    private fun latestLinkedTranscript(): TranscriptEntry? = ownedAudioId?.let { id ->
        app.transcriptHistory.forAudio(id).filter { it.file.isFile }.maxWithOrNull(compareBy<TranscriptEntry> { it.created }.thenBy { it.id })
    }

    private fun exposeStore(store: TranscriptStore) {
        currentStore?.keepForRecovery()
        currentStore = store
        synchronized(temporaryResults) { temporaryResults.add(store.file) }
        ownedAudioId = ownedAudioId ?: store.source.audioId
        if (store.source.modelName.isNotBlank()) chosenModel = store.source.modelName
        resultModelId = store.source.modelId ?: resultModelId
        lastRequestedModelId = resultModelId ?: lastRequestedModelId
        val entry = app.transcriptHistory.find(store.source.transcriptId)
        latestPreview = store.preview()
        document.show(store)
        mutable.update { it.copy(store = store, preview = latestPreview, transcriptBytes = store.bytes,
            transcriptId = entry?.id, transcriptPinned = entry?.retention?.pinned == true, model = chosenModel) }
    }

    fun retry(useCurrentModel: Boolean = false) {
        if (state.value.running || state.value.importing || state.value.saving || state.value.closing || state.value.deleting || operation?.isActive == true) return
        operation = viewModelScope.launch { transcribe(useCurrentModel) }
    }

    private suspend fun transcribe(useCurrentModel: Boolean = false) {
        val audioId = ownedAudioId ?: return
        metrics = CaptureMetrics().also { it.setVisualRefreshRate(state.value.visualRate) }
        fileProgress = FileTranscriptionProgress()
        mutable.update { it.copy(running = true, fileProgress = fileProgress.snapshot(metrics.state.value), message = null) }
        var session: TranscriptionSession? = null
        var presenter: Job? = null
        var activity: Job? = null
        var diagnostics: Job? = null
        var diagnosticSession: io.github.lrq3000.utterlane.diagnostics.CaptureDiagnosticSession? = null
        try {
            TranscriptionPower(app).use {
                withContext(Dispatchers.IO) {
                    app.recordingHistory.acquire(audioId).use { audioLease ->
                        app.modelManager.initializeSelection()
                        val selected = lastRequestedModelId
                        if (!useCurrentModel && selected != null && app.modelManager.selected.value.id != selected) {
                            val definition = CustomModelManifest.load(app.filesDir, selected) ?: ModelCatalog.find(selected)
                            check(definition.id == selected) { "The previous model is unavailable; choose another model" }
                            app.recognizerManager.selectModel(definition)
                        }
                        lastRequestedModelId = app.modelManager.selected.value.id
                        val source = app.recordingHistory.get(audioId)
                        // Captured PCM has an exact total. Imported container duration
                        // is provisional until decoding establishes the real EOF.
                        val total = if (source.sourceName == null) source.samples else
                            source.durationMs.takeIf { it in 1..(Long.MAX_VALUE / 16) }?.times(16)
                        fileProgress = FileTranscriptionProgress(total, estimatedTotal = source.sourceName != null)
                        val options = app.settingsRepository.runtimeOptions.first()
                        val retainText = app.settingsRepository.transcriptHistoryEnabled.first()
                        val textDuration = app.settingsRepository.transcriptHistoryRetention.first()
                        diagnosticSession = app.recognitionDiagnostics.capture(options)
                        diagnostics = viewModelScope.launch { metrics.state.collect { diagnosticSession?.record(it) } }
                        activity = viewModelScope.launch { app.recognizerManager.activity.collect { metrics.recognition(it) } }
                        presenter = viewModelScope.launch {
                            while (isActive) {
                                metrics.tick()
                                document.refresh()
                                publishTranscriptionState()
                                delay(VisualRefreshRate.intervalMillis(mutable.value.visualRate))
                            }
                        }
                        session = app.recognizerManager.createSession(options, onProcessed = metrics::processed) {
                            latestPreview = checkNotNull(session).store.preview()
                        }
                        val created = checkNotNull(session)
                        fileProgress.start(created.hasSpeakerFinalization)
                        chosenModel = app.modelManager.selected.value.name
                        resultModelId = app.modelManager.selected.value.id
                        lastRequestedModelId = resultModelId
                        created.store.attachSource(TranscriptSource(audioId, modelName = chosenModel, modelId = resultModelId))
                        exposeStore(created.store)
                        publishTranscriptionState()
                        val accept: suspend (ShortArray) -> Unit = { pcm -> metrics.captured(pcm.size); created.accept(pcm) }
                        if (source.sourceName != null) AudioDecoder(app).decode(source.part(0).absolutePath, accept)
                        else audioLease.reader().use { reader ->
                            while (reader.offset < source.samples) {
                                currentCoroutineContext().ensureActive()
                                val pcm = reader.read()
                                check(pcm.isNotEmpty()) { "Recording became unavailable" }
                                accept(pcm)
                            }
                        }
                        metrics.captureEnded()
                        fileProgress.inputEnded(metrics.state.value.capturedSamples)
                        publishTranscriptionState()
                        created.finish { speakers ->
                            fileProgress.finalizing(speakers)
                            publishTranscriptionState()
                        }
                        fileProgress.saving()
                        publishTranscriptionState()
                        if (retainText && textDuration != HistoryRetention.NONE && created.store.segments > 0) {
                            try {
                                val saved = app.transcriptHistory.save(created.store.file, chosenModel, audioId, modelId = resultModelId)
                                created.store.attachSource(created.store.source.copy(transcriptId = saved.id))
                                mutable.update { it.copy(transcriptId = saved.id) }
                            } catch (_: TranscriptDiscardedException) {
                                Log.i("TranscribeDialog", "Transcript autosave skipped after explicit deletion")
                            }
                        }
                        app.recordingHistory.completeRecovery(audioId, app.settingsRepository.audioHistoryRetention.first())
                        metrics.completed(null)
                        fileProgress.complete()
                        latestPreview = created.store.preview()
                        document.refresh()
                        publishTranscriptionState()
                        mutable.update { it.copy(
                            message = if (created.store.segments == 0) app.getString(R.string.toast_no_speech) else null) }
                    }
                }
            }
        } catch (e: CancellationException) { metrics.cancelled(); fileProgress.fail(cancelled = true); throw e
        } catch (e: Exception) {
            metrics.completed(e.message); fileProgress.fail(); showError(e)
            withContext(Dispatchers.IO) { app.recordingHistory.recordFailure(audioId, e.message.orEmpty(), if (session == null) "MODEL" else "INFERENCE") }
        } finally {
            presenter?.cancel(); activity?.cancel(); diagnostics?.cancel()
            diagnosticSession?.record(metrics.state.value)
            try { session?.close() } catch (e: Exception) { Log.e("TranscribeDialog", "Recognition cleanup failed", e) }
            session?.store?.takeIf { it !== currentStore }?.dispose()
            // A last segment can arrive after the final presentation tick. Failure
            // and cancellation must publish it too, particularly at slow UI rates.
            val surviving = currentStore
            latestPreview = surviving?.preview().orEmpty()
            document.refresh()
            publishTranscriptionState()
            mutable.update { it.copy(running = false) }
        }
    }

    /** One consistent presentation snapshot, including terminal updates between
     * timer ticks. The producer never reads the transcript body to calculate ETA. */
    private fun publishTranscriptionState() {
        val capture = metrics.state.value
        mutable.update { it.copy(fileProgress = fileProgress.snapshot(capture), capture = capture,
            preview = latestPreview, transcriptBytes = currentStore?.bytes ?: 0) }
    }

    fun saveAudioToHistory() = saveAction {
        persistAudioPin(true)
    }

    private suspend fun persistAudioPin(pinned: Boolean) {
        val audio = checkNotNull(linkedHistory.availableAudio(ownedAudioId)) { "Recording is unavailable" }
        if (audio.pinned != pinned)
            app.recordingHistory.setPinned(audio.id, pinned, app.settingsRepository.audioHistoryRetention.first(), app.historyCleanup.launchToken)
        refreshAudio()
    }

    fun saveTranscriptToHistory() = saveAction {
        persistTranscriptPin(true)
    }

    fun setPinned(target: DialogPinTarget, pinned: Boolean) = saveAction(successMessage = null) {
        // Validate both targets before changing either. Transcript scope is always
        // the displayed version, even when deletion uses an all-linked scope.
        if (target != DialogPinTarget.TRANSCRIPT)
            checkNotNull(linkedHistory.availableAudio(ownedAudioId)) { "Recording is unavailable" }
        if (target != DialogPinTarget.AUDIO) {
            check(!state.value.running) { "Wait for the current attempt to finish" }
            check(currentStore?.bytes?.let { it > 0 } == true) { "There is no transcript to save" }
            persistTranscriptPin(pinned)
        }
        if (target != DialogPinTarget.TRANSCRIPT) persistAudioPin(pinned)
    }

    private suspend fun persistTranscriptPin(pinned: Boolean) {
        check(!state.value.running) { "Wait for the current attempt to finish" }
        val store = checkNotNull(currentStore) { "There is no transcript to save" }
        check(!TranscriptSource.read(store.file).discarded) { "Transcript was deleted" }
        val existing = app.transcriptHistory.find(mutable.value.transcriptId)
        if (existing == null && !pinned) {
            // A stale Unpin choice must never save a replacement for an entry
            // removed elsewhere while the popup was open.
            mutable.update { it.copy(transcriptId = null, transcriptPinned = false) }
            return
        }
        val saved = if (existing != null) {
            if (existing.retention.pinned != pinned)
                app.transcriptHistory.setPinned(existing.id, pinned, app.settingsRepository.transcriptHistoryRetention.first(), app.historyCleanup.launchToken)
            app.transcriptHistory.get(existing.id)
        } else app.transcriptHistory.save(store.file, chosenModel, ownedAudioId, pinned = true, modelId = resultModelId)
        store.attachSource(TranscriptSource(ownedAudioId, saved.id, chosenModel, resultModelId))
        mutable.update { it.copy(transcriptId = saved.id, transcriptPinned = saved.retention.pinned) }
    }

    fun shareAudio(launch: (android.content.Intent) -> Unit) = saveAction(successMessage = null) {
        val intent = audioActions.share(checkNotNull(ownedAudioId))
        withContext(Dispatchers.Main) { launch(intent) }
    }
    fun exportAudio(destination: Uri, directory: Boolean) = saveAction {
        audioActions.export(checkNotNull(ownedAudioId), destination, directory)
    }
    private fun saveAction(successMessage: Int? = R.string.dialog_audio_action_done, action: suspend () -> Unit) {
        if (state.value.saving || state.value.closing || state.value.deleting) return
        saving = viewModelScope.launch {
            mutable.update { it.copy(saving = true) }
            try {
                withContext(Dispatchers.IO) { action() }
                successMessage?.let { message -> mutable.update { it.copy(message = app.getString(message)) } }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }

    private fun workingTranscriptId(): String? = currentStore?.takeIf { it.bytes > 0 }?.let {
        it.source.transcriptId ?: TranscriptHistory.idForAttempt(it.file.name)
    }

    private fun workingFiles(): List<File> = synchronized(temporaryResults) { temporaryResults.toList() }
    private fun workingId(file: File): String = TranscriptSource.read(file).transcriptId ?: TranscriptHistory.idForAttempt(file.name)
    private fun cachedWorkingCopies() = TranscriptSource.copies(File(app.cacheDir, "transcripts"))
    private fun linkedWorkingCopies(): List<WorkingTranscriptCopy> {
        val audioId = ownedAudioId ?: return emptyList()
        return cachedWorkingCopies().filter { it.source.audioId == audioId }
    }

    private fun deletionPlan(): HistoryDeletionPlan {
        val plan = linkedHistory.plan(ownedAudioId, mutable.value.transcriptId, input.transcriptOrigin, workingTranscriptId())
        if (!plan.allLinked) return plan
        // Earlier unsaved attempts also belong to this recording. Count each
        // identity once, even if history and recovery both contain a copy.
        val ids = plan.transcriptIds.toMutableSet()
        workingFiles().filter { it.isFile && it.length() > 0 && !TranscriptSource.read(it).discarded }
            .forEach { ids.add(workingId(it)) }
        linkedWorkingCopies().forEach { ids.add(it.id) }
        return plan.copy(transcriptIds = ids.toSet())
    }

    private fun deleteWorkingCopies(ids: Set<String>) {
        val selected = workingFiles().filter { it.isFile && workingId(it) in ids }
        // A saved transcript can also have recovery copies from an earlier dialog.
        // Mark all confirmed identities before deleting the authoritative history.
        val copies = cachedWorkingCopies().filter { it.id in ids }.map { it.file }
        (selected + copies).toSet().forEach(TranscriptStore::deleteArtifacts)
        synchronized(temporaryResults) { temporaryResults.removeAll(selected.toSet()) }
    }

    fun requestDeletion() {
        if (state.value.importing || state.value.saving || state.value.closing || state.value.deleting || state.value.checkingDeletion) return
        mutable.update { it.copy(checkingDeletion = true) }
        viewModelScope.launch {
            try {
                val plan = withContext(Dispatchers.IO) { deletionPlan() }
                mutable.update { it.copy(deletion = plan.takeIf { it.choices.isNotEmpty() }?.let(::DialogDeletionRequest),
                    message = if (plan.choices.isEmpty()) app.getString(R.string.dialog_nothing_to_delete) else it.message) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { showError(e)
            } finally { mutable.update { it.copy(checkingDeletion = false) } }
        }
    }

    fun chooseDeletion(target: HistoryDeletionTarget) {
        mutable.update { old -> old.copy(deletion = old.deletion?.takeIf { target in it.plan.choices }?.copy(target = target)) }
    }
    fun cancelDeletion() { mutable.update { it.copy(deletion = null) } }
    fun dismissDeletionMenu() { mutable.update { if (it.deletion?.target == null) it.copy(deletion = null) else it } }

    fun confirmDeletion() {
        val request = state.value.deletion ?: return
        val target = request.target ?: return
        if (state.value.deleting || state.value.closing) return
        mutable.update { it.copy(deleting = true, deletion = null) }
        app.audioPlayback.stop(playbackOwner)
        app.applicationScope.launch {
            try {
                // Confirmation, not the first trash tap, cancels this attempt.
                // Joining prevents a pending autosave from resurrecting deleted IDs.
                operation?.cancelAndJoin(); saving?.join()
                withContext(Dispatchers.IO) {
                    val fresh = deletionPlan()
                    val expanded = target != HistoryDeletionTarget.AUDIO && !request.plan.transcriptIds.containsAll(fresh.transcriptIds)
                    val changedAudio = target != HistoryDeletionTarget.TRANSCRIPTS && fresh.audioId != null && fresh.audioId != request.plan.audioId
                    if (expanded || changedAudio) {
                        mutable.update { it.copy(deletion = DialogDeletionRequest(fresh, target.takeIf { it in fresh.choices }),
                            message = app.getString(R.string.dialog_deletion_changed)) }
                        return@withContext
                    }
                    if (target != HistoryDeletionTarget.AUDIO) deleteWorkingCopies(request.plan.transcriptIds)
                    linkedHistory.delete(request.plan, target)
                    if (target != HistoryDeletionTarget.AUDIO && workingTranscriptId() in request.plan.transcriptIds) {
                        document.show(null)
                        currentStore?.dispose(); currentStore = null; latestPreview = ""
                        mutable.update { it.copy(store = null, preview = "", transcriptBytes = 0, transcriptId = null,
                            transcriptPinned = false, fileProgress = null) }
                    }
                    if (target != HistoryDeletionTarget.TRANSCRIPTS) ownedAudioId?.let { RecordingRecovery.dismissNotification(app, it) }
                    refreshAudio(); refreshTranscript()
                    val empty = deletionPlan().choices.isEmpty()
                    if (empty) workingFiles().forEach(TranscriptStore::deleteArtifacts)
                    mutable.update { it.copy(finished = empty, message = app.getString(R.string.dialog_deleted)) }
                }
            } catch (e: Exception) { showError(e)
            } finally { mutable.update { it.copy(deleting = false) } }
        }
    }

    /** Dismissal is an explicit action, never inferred from onStop/onDestroy. */
    fun dismiss(done: () -> Unit) {
        if (state.value.closing || state.value.deleting) return
        mutable.update { it.copy(closing = true) }
        app.audioPlayback.stop(playbackOwner)
        val task = operation
        app.applicationScope.launch {
            try {
                // Persist dismissal before waiting on any long-running owner.
                // Existing export/read leases defer physical deletion, but a
                // process death must not resurrect explicitly discarded input.
                withContext(Dispatchers.IO) {
                    if (!input.transcriptOrigin) ownedAudioId?.let(app.recordingHistory::dismiss)
                }
                task?.cancelAndJoin()
                saving?.join()
                withContext(Dispatchers.IO) {
                    // Import completion can race dismissal; ownership was published
                    // on IO before returning so this second pass cannot orphan it.
                    if (!input.transcriptOrigin) ownedAudioId?.let(app.recordingHistory::dismiss)
                    if (!input.transcriptOrigin) ownedAudioId?.let { RecordingRecovery.dismissNotification(app, it) }
                    currentStore?.dispose(); currentStore = null
                    synchronized(temporaryResults) { temporaryResults.toList() }.forEach(TranscriptStore::deleteArtifacts)
                }
                done()
            } catch (e: Exception) {
                showError(e); mutable.update { it.copy(closing = false) }
            }
        }
    }

    private fun showError(error: Exception) {
        Log.e("TranscribeDialog", "Local transcription operation failed", error)
        mutable.update { it.copy(message = error.message ?: app.getString(R.string.transcribe_error_failed)) }
    }
    fun saveInstanceState(out: android.os.Bundle) {
        out.putString("owned_audio", ownedAudioId)
        out.putString("working_text", currentStore?.file?.absolutePath)
        out.putString("saved_text", state.value.transcriptId)
        out.putBoolean("text_origin", input.transcriptOrigin)
        out.putString("result_model", chosenModel)
        out.putString("result_model_id", resultModelId)
    }
    override fun onCleared() {
        // Unexpected owner destruction preserves disk-backed input and useful
        // partial text. Explicit dismiss already deleted/released its own work.
        currentStore?.keepForRecovery()
        app.audioPlayback.stop(playbackOwner)
        super.onCleared()
    }
}
