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
import io.github.lrq3000.utterlane.util.StorageFailure
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DialogInput(val uri: Uri? = null, val path: String? = null, val audioId: String? = null,
    val transcriptId: String? = null, val transcriptPath: String? = null, val automatic: Boolean = false,
    val transcriptOrigin: Boolean = transcriptId != null, val modelName: String = "", val modelId: String? = null,
    val metadata: TranscriptMetadata? = null, val recovered: Boolean = false)

data class TranscriptionDialogState(
    val audio: HistoryEntry? = null, val store: TranscriptStore? = null, val transcriptId: String? = null,
    val preview: String = "", val transcriptBytes: Long = 0, val transcriptPinned: Boolean = false, val running: Boolean = false,
    val importing: Boolean = false, val saving: Boolean = false, val closing: Boolean = false,
    val importQuestion: ImportRetentionRequest? = null, val importCancelled: Boolean = false,
    val message: String? = null, val model: String = "",
    val fileProgress: FileProgressSnapshot? = null,
    val capture: CaptureSnapshot = CaptureSnapshot(), val visualRate: Int = VisualRefreshRate.DEFAULT,
    val deletion: DialogDeletionRequest? = null, val checkingDeletion: Boolean = false,
    val deleting: Boolean = false, val finished: Boolean = false,
    val exitRequest: DialogExitRequest? = null, val checkingExit: Boolean = false, val exited: Boolean = false,
    val audioExporting: Boolean = false
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
    private val importRetention = ImportRetentionGate(
        question = { request -> mutable.update { it.copy(importQuestion = request) } },
        remember = app.settingsRepository::rememberImportRetention)
    private val linkedHistory = LinkedHistory(app.recordingHistory, app.transcriptHistory)
    private val historyOwners = DialogHistoryOwners(app.recordingHistory, app.transcriptHistory)
    private val exitRetention = DialogExitRetention(app)
    val document = TranscriptPager(viewModelScope)
    private var operation: Job? = null
    private var saving: Job? = null
    private val audioExportSession = AudioExportSession()
    private var exportPreparation: Job? = null
    @Volatile private var ownedAudioId = input.audioId
    private val temporaryResults = linkedSetOf<File>()
    @Volatile private var currentStore: TranscriptStore? = null
    @Volatile private var latestPreview = ""
    @Volatile private var fileProgress = FileTranscriptionProgress()
    private var chosenModel = input.modelName
    private var resultModelId: String? = input.modelId
    private var lastRequestedModelId: String? = input.modelId
    @Volatile private var resultMetadata = input.metadata ?: TranscriptMetadata()
    /** Snapshot for a retained owner/journal; it survives disappearance of audio. */
    val metadata: TranscriptMetadata get() = resultMetadata
    var metrics = CaptureMetrics()
        private set

    init {
        viewModelScope.launch { app.settingsRepository.visualRefreshRate.collect { rate ->
            metrics.setVisualRefreshRate(rate); mutable.update { it.copy(visualRate = rate) }
        } }
        viewModelScope.launch(Dispatchers.IO) { app.recordingHistory.revision.collect { refreshAudio() } }
        viewModelScope.launch(Dispatchers.IO) { app.transcriptHistory.revision.collect { refreshTranscript() } }
        operation = viewModelScope.launch {
            var archival: io.github.lrq3000.utterlane.archive.ArchivalCoordinator.Producer? = null
            try {
                if (input.uri != null || input.path != null) archival = app.archival.producer()
                withContext(Dispatchers.IO) {
                    val entry = input.transcriptId?.let(app.transcriptHistory::find)
                        ?: if (input.transcriptId == null && input.transcriptPath == null && !input.automatic)
                            latestLinkedTranscript() else null
                    if (entry != null) {
                        ownedAudioId = ownedAudioId ?: entry.audioId
                        chosenModel = entry.model; resultModelId = entry.modelId; lastRequestedModelId = entry.modelId
                        resultMetadata = TranscriptMetadata(entry)
                        mutable.update { it.copy(transcriptId = entry.id, model = entry.model, transcriptPinned = entry.retention.pinned) }
                    }
                    val restored = input.transcriptPath?.let { File(it).canonicalFile }?.takeIf { file ->
                        require(file.parentFile == File(app.cacheDir, "transcripts").canonicalFile) { "Transcript is unavailable" }
                        file.isFile
                    }
                    if (restored != null) {
                        // Pre-provenance Home journals carried these identities in
                        // DialogInput. Migrate that association without replacing
                        // newer sidecar identities or clearing a discard marker.
                        exposeStore(app.transcriptHistory.migrateWorking(TranscriptStore(restored),
                            TranscriptSource(ownedAudioId, entry?.id, chosenModel, resultModelId)))
                    }
                    else if (entry != null) {
                        app.transcriptHistory.acquire(entry.id).use {
                            val directory = File(app.cacheDir, "transcripts").apply { mkdirs() }
                            val copy = File.createTempFile("view-", ".txt", directory)
                            entry.file.copyTo(copy, overwrite = true)
                            // Publish the owner on IO before returning across the
                            // cancellable Main-dispatch boundary; dismissal joins it.
                            exposeStore(app.transcriptHistory.migrateWorking(TranscriptStore(copy),
                                TranscriptSource(entry.audioId, entry.id, entry.model, entry.modelId)))
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
                    val preferences = app.settingsRepository.importRetentionPreferences.first()
                    val source = runInterruptible(Dispatchers.IO) { audioActions.inspect(input.uri, input.path) }
                    val choice = importRetention.resolve(source.metadata, preferences)
                    if (choice == null) {
                        mutable.update { it.copy(importCancelled = true) }
                        return@launch
                    }
                    runInterruptible(Dispatchers.IO) {
                        // Publish ownership before dispatch back to Main, including
                        // when cancellation races completion of the source copy.
                        // The import lease bridges publication to the dialog owner;
                        // a late onCleared closes it rather than leaking an owner.
                        val owned = audioActions.import(source, choice)
                        try {
                            ownedAudioId = owned.entry.id
                            historyOwners.audio(owned.entry.id)
                            archival?.audio(owned.entry.id)
                        } finally { owned.lease.close() }
                    }
                }
                withContext(Dispatchers.IO) { refreshAudio() }
                mutable.update { it.copy(importing = false) }
                if (input.automatic && ownedAudioId != null) transcribe(archival = archival)
                else mutable.value.audio?.takeIf {
                    // Recovery protection also owns successful temporary results;
                    // only a real failure/interruption warrants recovery wording.
                    it.needsRecovery && (it.status == "failed" || it.status == "interrupted" ||
                        it.failureKind != null || it.failureMessage != null)
                }?.let { recovery ->
                    mutable.update {
                        it.copy(message = if (recovery.failureKind == "MODEL") app.getString(R.string.dialog_model_failed)
                            else recovery.failureMessage ?: app.getString(R.string.dialog_recovery_info))
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { showError(e)
            } finally { archival?.close(); mutable.update { it.copy(importing = false) } }
        }
    }

    private fun refreshAudio() {
        if (input.recovered) ownedAudioId?.let(app.recordingHistory::markRecovered)
        val id = ownedAudioId
        val audio = linkedHistory.availableAudio(id)
        historyOwners.audio(audio?.id) { ownedAudioId == id }
        // Source deletion or a later re-transcription must not erase/change the
        // metadata attached to the text this dialog already owns.
        if (resultMetadata.created == null && audio != null) {
            // Interrupted live text can checkpoint labels before the audio
            // finalizer updates its index. Hydration fills chronology, not erases
            // that independent evidence of already-committed labeled output.
            resultMetadata = TranscriptMetadata(audio, speakerLabels = audio.speakerLabels || resultMetadata.speakerLabels)
                .copy(recovered = audio.recovered || resultMetadata.recovered)
        }
        if (audio?.recovered == true) resultMetadata = resultMetadata.copy(recovered = true)
        mutable.update { if (ownedAudioId == id) it.copy(audio = audio) else it }
    }

    private fun refreshTranscript() {
        val id = mutable.value.transcriptId
        val entry = app.transcriptHistory.find(id)
        // Release deleted identities even when the dialog still displays the
        // independent other kind. A stale refresh cannot release a newer owner.
        historyOwners.transcript(entry?.id) { mutable.value.transcriptId == id }
        mutable.update { if (it.transcriptId == id) it.copy(transcriptPinned = entry?.retention?.pinned == true) else it }
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
        if (entry != null) resultMetadata = TranscriptMetadata(entry)
        // Provenance belongs to the text as well as the source. It must survive
        // source expiry, opening through a text-only recovery link, and retries.
        if (input.recovered || resultMetadata.recovered || store.source.recovered ||
            linkedHistory.availableAudio(ownedAudioId)?.recovered == true) {
            resultMetadata = resultMetadata.copy(recovered = true)
            store.attachSource(store.source.copy(recovered = true))
            entry?.let { app.transcriptHistory.markRecovered(it.id) }
        }
        latestPreview = store.preview()
        document.show(store)
        mutable.update { it.copy(store = store, preview = latestPreview, transcriptBytes = store.bytes,
            transcriptId = entry?.id, transcriptPinned = entry?.retention?.pinned == true, model = chosenModel) }
        refreshTranscript()
    }

    fun retry(useCurrentModel: Boolean = false) {
        if (state.value.exitRequest != null || state.value.checkingExit || state.value.running || state.value.importing || state.value.saving || state.value.closing || state.value.deleting || operation?.isActive == true) return
        operation = viewModelScope.launch { transcribe(useCurrentModel) }
    }

    fun chooseImportRetention(choice: ImportRetentionChoice, remember: Boolean) = importRetention.answer(choice, remember)
    fun cancelImport() = importRetention.cancel()

    private suspend fun transcribe(useCurrentModel: Boolean = false,
        archival: io.github.lrq3000.utterlane.archive.ArchivalCoordinator.Producer? = null) {
        val audioId = ownedAudioId ?: return
        val archiveProducer = archival ?: app.archival.producer()
        metrics = CaptureMetrics().also { it.setVisualRefreshRate(state.value.visualRate) }
        fileProgress = FileTranscriptionProgress()
        mutable.update { it.copy(running = true, fileProgress = fileProgress.snapshot(metrics.state.value), message = null) }
        var session: TranscriptionSession? = null
        var processing: RecordingHistory.ProcessingAttempt? = null
        var presenter: Job? = null
        var activity: Job? = null
        var diagnostics: Job? = null
        var diagnosticSession: io.github.lrq3000.utterlane.diagnostics.CaptureDiagnosticSession? = null
        try {
            TranscriptionPower(app).use {
                withContext(Dispatchers.IO) {
                    app.recordingHistory.acquire(audioId, protectFromPruning = true).use { audioLease ->
                        processing = app.recordingHistory.beginProcessing(audioId)
                        app.modelManager.initializeSelection()
                        val selected = lastRequestedModelId
                        if (!useCurrentModel && selected != null && app.modelManager.selected.value.id != selected) {
                            val definition = CustomModelManifest.load(app.filesDir, selected) ?: ModelCatalog.find(selected)
                            check(definition.id == selected) { "The previous model is unavailable; choose another model" }
                            app.recognizerManager.selectModel(definition)
                        }
                        lastRequestedModelId = app.modelManager.selected.value.id
                        val source = app.recordingHistory.processingAudio(app.recordingHistory.get(audioId))
                        val gain = io.github.lrq3000.utterlane.audio.TranscriptionGain(
                            source.microphone?.gain ?: io.github.lrq3000.utterlane.audio.PcmGainMode.OFF)
                        // Captured PCM has an exact total. Imported container duration
                        // is provisional until decoding establishes the real EOF.
                        val total = if (source.sourceName == null) source.samples else
                            source.durationMs.takeIf { it in 1..(Long.MAX_VALUE / 16) }?.times(16)
                        fileProgress = FileTranscriptionProgress(total, estimatedTotal = source.sourceName != null)
                        val options = app.settingsRepository.runtimeOptions.first()
                        val retainText = app.settingsRepository.transcriptHistoryEnabled.first() || archiveProducer.textPlan != null
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
                            val current = checkNotNull(session)
                            // A checkpoint taken while processing must describe
                            // already-committed labels, including partial results.
                            if (current.hasSpeakerLabels && !resultMetadata.speakerLabels) {
                                resultMetadata = resultMetadata.copy(speakerLabels = true)
                            }
                            latestPreview = current.store.preview()
                        }
                        val created = checkNotNull(session)
                        fileProgress.start(created.hasSpeakerFinalization)
                        chosenModel = app.modelManager.selected.value.name
                        resultModelId = app.modelManager.selected.value.id
                        lastRequestedModelId = resultModelId
                        created.store.attachSource(TranscriptSource(audioId, modelName = chosenModel, modelId = resultModelId,
                            recovered = source.recovered, archivePlan = archiveProducer.textPlan))
                        resultMetadata = TranscriptMetadata(source, speakerLabels = false)
                        exposeStore(created.store)
                        publishTranscriptionState()
                        val accept: suspend (ShortArray) -> Unit = { pcm -> metrics.captured(pcm.size); gain.deliver(pcm, created::accept) }
                        val original = audioLease.originalSource
                        if (original != null) {
                            if (original.nativePcm) audioLease.originalPcmReader().use { reader ->
                                while (reader.offset < original.samples) {
                                    currentCoroutineContext().ensureActive()
                                    accept(reader.read())
                                }
                            } else for (part in 0 until original.parts) AudioDecoder(app).decodePart(original.asset(part), accept)
                        } else if (audioLease.originalImportFile != null) {
                            // A concurrently completed optional conversion must
                            // not switch original import ASR onto lossy output.
                            AudioDecoder(app).decode(audioLease.originalImportFile.absolutePath, accept)
                        } else if (source.sourceName != null || source.rollingAudio != null) {
                            app.recordingHistory.prepareAudio(source)
                            for (part in 0 until source.parts) {
                                app.recordingHistory.preparePart(source, part)
                                AudioDecoder(app).decodePart(source.asset(part), accept)
                            }
                        }
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
                        gain.drain(created::accept)
                        created.finish { speakers ->
                            fileProgress.finalizing(speakers)
                            publishTranscriptionState()
                        }
                        resultMetadata = resultMetadata.copy(speakerLabels = created.hasSpeakerLabels)
                        fileProgress.saving()
                        publishTranscriptionState()
                        if (retainText && textDuration != HistoryRetention.NONE && created.store.segments > 0) {
                            try {
                                val saved = resultMetadata.save(app.transcriptHistory, created.store.file, chosenModel, audioId,
                                    modelId = resultModelId, archivePlan = archiveProducer.textPlan)
                                created.store.attachSource(created.store.source.copy(transcriptId = saved.id))
                                mutable.update { it.copy(transcriptId = saved.id) }
                                historyOwners.transcript(saved.id)
                            } catch (_: TranscriptDiscardedException) {
                                Log.i("TranscribeDialog", "Transcript autosave skipped after explicit deletion")
                            }
                        }
                        checkNotNull(processing).complete()
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
            withContext(Dispatchers.IO) {
                val kind = if (session == null) "MODEL" else "INFERENCE"
                processing?.fail(e.message.orEmpty(), kind)
                    ?: app.recordingHistory.recordFailure(audioId, e.message.orEmpty(), kind)
            }
        } finally {
            if (archival == null) archiveProducer.close()
            withContext(NonCancellable + Dispatchers.IO) {
                try { processing?.close() }
                catch (e: Exception) { Log.e("TranscribeDialog", "Could not record processing interruption", e) }
            }
            presenter?.cancel(); activity?.cancel(); diagnostics?.cancel()
            diagnosticSession?.record(metrics.state.value)
            try { session?.close() } catch (e: Exception) { Log.e("TranscribeDialog", "Recognition cleanup failed", e) }
            session?.takeIf { it.store === currentStore }?.let { completed ->
                // Partial labeled text is still a real result after inference
                // failure. Save the badge without requiring its source to exist.
                resultMetadata = resultMetadata.copy(speakerLabels = completed.hasSpeakerLabels)
                withContext(NonCancellable + Dispatchers.IO) {
                    try { app.recordingHistory.setSpeakerLabels(audioId, completed.hasSpeakerLabels) }
                    catch (e: Exception) { Log.e("TranscribeDialog", "Could not save speaker metadata", e) }
                }
            }
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

    fun saveAudioToHistory() = saveAction(R.string.action_feedback_pinned_audio) {
        persistAudioPin(true)
    }

    private suspend fun persistAudioPin(pinned: Boolean) {
        val audio = checkNotNull(linkedHistory.availableAudio(ownedAudioId)) { "Recording is unavailable" }
        if (audio.pinned != pinned)
            app.recordingHistory.setPinned(audio.id, pinned, app.settingsRepository.audioHistoryRetention.first(), app.historyCleanup.launchToken)
        refreshAudio()
        if (pinned) app.archival.producer().use { it.audio(audio.id) }
    }

    fun saveTranscriptToHistory() = saveAction(R.string.action_feedback_pinned_text) {
        persistTranscriptPin(true)
    }

    fun setPinned(target: DialogPinTarget, pinned: Boolean) = saveAction(
        if (!pinned) R.string.action_feedback_unpinned else when (target) {
            DialogPinTarget.AUDIO -> R.string.action_feedback_pinned_audio
            DialogPinTarget.TRANSCRIPT -> R.string.action_feedback_pinned_text
            DialogPinTarget.BOTH -> R.string.action_feedback_pinned_both
        }) {
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
        val retention = app.settingsRepository.transcriptHistoryRetention.first()
        app.transcriptHistory.withPublicationLock {
            val source = TranscriptSource.read(store.file)
            check(!source.discarded) { "Transcript was deleted" }
            // Another owner may already have kept this same working file. Its
            // durable identity supersedes our older presentation snapshot.
            val existing = source.transcriptId ?: mutable.value.transcriptId
            val present = existing?.let {
                app.transcriptHistory.setPinnedIfPresent(it, pinned, retention, app.historyCleanup.launchToken)
            }
            val saved = if (present != null) {
                store.attachSource(TranscriptSource(ownedAudioId, present.id, chosenModel, resultModelId))
                present
            } else if (pinned) {
                // Complete fresh identity + provenance publication, with rollback
                // on failure, shares confirmation's recheck/mark transaction.
                app.transcriptHistory.saveWorking(store, chosenModel, ownedAudioId, resultModelId, resultMetadata)
            } else null
            mutable.update { it.copy(transcriptId = saved?.id, transcriptPinned = saved?.retention?.pinned == true) }
        }
        // Publication emits a repository revision before state owns the new ID.
        // Acquire explicitly too, rather than depending on collector scheduling.
        refreshTranscript()
        if (pinned) app.archival.producer().use { producer ->
            mutable.value.transcriptId?.let { producer.text(it) }
        }
    }

    /** Publication is a small local history action. The potentially slow folder
     * writer belongs to the application supervisor, never result.saving/Home.busy. */
    fun saveMarkdown(folder: String? = null) {
        if (state.value.running || state.value.deleting || state.value.closing || state.value.checkingExit) return
        val store = currentStore ?: return
        val selected = io.github.lrq3000.utterlane.archive.MarkdownSelection(store, chosenModel, ownedAudioId, resultModelId, resultMetadata)
        io.github.lrq3000.utterlane.archive.ManualMarkdownSave(app.applicationScope, app.transcriptHistory).start(selected,
            prepare = {
                val producer = app.archival.producer(manual = true)
                try {
                    val destination = folder ?: producer.settings.markdownFolder ?: error(app.getString(R.string.archive_no_folder))
                    io.github.lrq3000.utterlane.archive.PreparedMarkdownSave(
                        io.github.lrq3000.utterlane.archive.ArchivePlan(destination, producer.epoch), producer::close)
                } catch (error: Exception) { producer.close(); throw error }
            }, onPublished = { result ->
                // Re-evaluated inside StateFlow's CAS loop: retry may expose a new
                // store between IO completion and this callback reaching Main.
                mutable.update { old -> if (result.canUpdate(currentStore, old.store))
                    old.copy(transcriptId = result.entry.id, transcriptPinned = result.entry.retention.pinned) else old }
                app.applicationScope.launch(Dispatchers.IO) {
                    if (result.canUpdate(currentStore, mutable.value.store)) refreshTranscript()
                }
                ActionFeedback.show(app, if (result.state == io.github.lrq3000.utterlane.archive.ArchivalState.PUBLISHED)
                    R.string.archive_already_saved else R.string.archive_queued)
            }, onFailure = { error ->
                if (currentStore === selected.store) showError(error)
                else {
                    Log.e("TranscribeDialog", "An earlier Markdown save failed", error)
                    ActionFeedback.show(app, error.message ?: app.getString(R.string.transcribe_error_failed))
                }
            })
    }

    private fun exportBlocked() = state.value.audioExporting || state.value.exitRequest != null || state.value.checkingExit ||
        state.value.closing || state.value.deleting || state.value.exited
    private suspend fun releaseExport(action: () -> Unit) {
        try { withContext(NonCancellable + Dispatchers.IO) { action() } }
        catch (error: Exception) { Log.e("TranscribeDialog", "Export source cleanup failed; private history is preserved", error) }
    }

    fun prepareAudioExport(launch: (AudioExportRequest) -> Unit) {
        if (exportBlocked()) return
        val id = ownedAudioId ?: return
        mutable.update { it.copy(audioExporting = true) }
        exportPreparation = viewModelScope.launch {
            var selection: AudioExportSelection? = null
            var installed = false
            var launched = false
            try {
                // Publish the lease to the cleanup holder before IO returns: a
                // cancelled dispatch must not lose its only closeable reference.
                withContext(Dispatchers.IO) { selection = app.recordingHistory.selectForExport(id) }
                val selected = checkNotNull(selection)
                audioExportSession.install(selected); selection = null; installed = true
                launch(AudioExportRequest(selected.name, selected.mimeType, selected.parts > 1))
                launched = true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { showError(error)
            } finally {
                releaseExport { selection?.close() }
                if (installed && !launched) releaseExport { audioExportSession.cancel() }
                if (!launched) mutable.update { it.copy(audioExporting = false) }
            }
        }
    }

    fun finishAudioExport(destination: Uri?, directory: Boolean) {
        if (destination == null) {
            app.applicationScope.launch {
                releaseExport { audioExportSession.cancel() }
                mutable.update { it.copy(audioExporting = false) }
            }
            return
        }
        val selected = try { audioExportSession.take() } catch (error: Exception) {
            mutable.update { it.copy(audioExporting = false) }; showError(error); return
        }
        // Ownership transfers from picker to application task. Navigation and
        // ViewModel destruction release only a still-pending picker, not this task.
        app.applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) { audioActions.export(selected, destination, directory) }
                ActionFeedback.show(app, R.string.action_feedback_audio_saved)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { showError(error)
            } finally {
                releaseExport { selected.close() }
                mutable.update { it.copy(audioExporting = false) }
            }
        }
    }

    /** Direct export callers have no picker interval; still bind metadata/bytes
     * once and do not hold result.saving/Home busy throughout provider transfer. */
    fun exportAudio(destination: Uri, directory: Boolean) {
        if (exportBlocked()) return
        val id = ownedAudioId ?: return
        mutable.update { it.copy(audioExporting = true) }
        app.applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var selection: AudioExportSelection? = null
            try {
                withContext(Dispatchers.IO) {
                    selection = app.recordingHistory.selectForExport(id)
                    audioActions.export(checkNotNull(selection), destination, directory)
                }
                ActionFeedback.show(app, R.string.action_feedback_audio_saved)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { showError(error)
            } finally {
                releaseExport { selection?.close() }
                mutable.update { it.copy(audioExporting = false) }
            }
        }
    }
    fun shareAudio(launch: (android.content.Intent) -> Unit) {
        if (exportBlocked()) return
        val id = ownedAudioId ?: return
        mutable.update { it.copy(audioExporting = true) }
        app.applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val intent = withContext(Dispatchers.IO) { audioActions.share(id) }
                launch(intent)
                ActionFeedback.show(app, R.string.action_feedback_share)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { showError(error)
            } finally { mutable.update { it.copy(audioExporting = false) } }
        }
    }
    private fun saveAction(successMessage: Int? = R.string.dialog_audio_action_done, action: suspend () -> Unit) {
        if (state.value.exitRequest != null || state.value.checkingExit || state.value.saving || state.value.closing || state.value.deleting) return
        saving = viewModelScope.launch {
            mutable.update { it.copy(saving = true) }
            try {
                withContext(Dispatchers.IO) { action() }
                successMessage?.let { ActionFeedback.show(app, it) }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }

    private fun workingTranscriptId(): String? = currentStore?.takeIf { it.bytes > 0 && it.file.isFile }?.let {
        // The model's store snapshot can predate another owner's Keep/discard.
        val source = TranscriptSource.read(it.file)
        if (source.discarded) null else source.transcriptId ?: TranscriptHistory.idForAttempt(it.file.name)
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
                    var discardedCurrent = false
                    val fresh = linkedHistory.confirmDeletion(request.plan, target, ::deletionPlan) { ids ->
                        // Snapshot selection before the durable marker makes the
                        // current source unavailable to subsequent availability IO.
                        discardedCurrent = workingTranscriptId() in ids
                        deleteWorkingCopies(ids)
                    }
                    if (fresh != null) {
                        mutable.update { it.copy(deletion = DialogDeletionRequest(fresh, target.takeIf { it in fresh.choices }),
                            message = app.getString(R.string.dialog_deletion_changed)) }
                        return@withContext
                    }
                    if (discardedCurrent) {
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

    /** Both user Back routes enter here. Merely showing the question does not
     * cancel processing; Go back can return to the still-running attempt. */
    fun requestExit() {
        if (state.value.closing || state.value.deleting || state.value.checkingExit || state.value.exitRequest != null) return
        mutable.update { it.copy(checkingExit = true) }
        viewModelScope.launch {
            try {
                saving?.join()
                val request = withContext(Dispatchers.IO) { exitRetention.check(ownedAudioId, currentStore) }
                if (request.hasLoss) mutable.update { it.copy(exitRequest = request) }
                else finishExit(pin = false, consent = null)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { showError(e)
            } finally { mutable.update { it.copy(checkingExit = false) } }
        }
    }

    fun cancelExit() {
        if (!state.value.closing) mutable.update { it.copy(exitRequest = null) }
    }
    fun confirmExit(pin: Boolean) {
        val request = state.value.exitRequest ?: return
        if (!state.value.closing) finishExit(pin, request)
    }

    private fun finishExit(pin: Boolean, consent: DialogExitRequest?) {
        mutable.update { it.copy(closing = true) }
        app.audioPlayback.stop(playbackOwner)
        app.applicationScope.launch {
            // Partial/recovered text may publish on Back, after its original ASR
            // producer ended. Acquire service ownership before this visible UI
            // closes, while retaining the working source's cancellation epoch.
            val archiveOwner = app.archival.producer(manual = currentStore?.source?.archivePlan != null)
            try {
                // Import and final partial-text publication can race Back. Join
                // first, then recheck loss against the actual surviving content.
                operation?.cancelAndJoin(); saving?.join()
                val renewed = withContext(Dispatchers.IO) {
                    if (pin) {
                        if (currentStore?.bytes?.let { it > 0 } == true) persistTranscriptPin(true)
                        if (linkedHistory.availableAudio(ownedAudioId) != null) persistAudioPin(true)
                    }
                    exitRetention.finish(ownedAudioId, currentStore, resultMetadata, if (pin) null else consent, archiveOwner.textPlan)
                }
                if (renewed != null) {
                    mutable.update { it.copy(exitRequest = renewed, closing = false) }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ownedAudioId?.let { RecordingRecovery.dismissNotification(app, it) }
                    releaseWorkingResults()
                }
                if (pin) ActionFeedback.show(app, R.string.action_feedback_exit_pinned)
                mutable.update { it.copy(exited = true, exitRequest = null) }
            } catch (e: Exception) {
                showError(e); mutable.update { it.copy(closing = false) }
            } finally { archiveOwner.close() }
        }
    }

    /** Explicit Home Reset/replacement, never inferred from onStop/onDestroy.
     * Unlike navigation, these callers have already decided to retire an owner. */
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
                    releaseWorkingResults()
                }
                done()
            } catch (e: Exception) {
                showError(e); mutable.update { it.copy(closing = false) }
            }
        }
    }

    /** Release only after disposition is durable, whether navigation retained the
     * result or an explicit Home action retired it. All payload cleanup is on IO. */
    private fun releaseWorkingResults() {
        exportPreparation?.cancel()
        audioExportSession.close()
        currentStore?.dispose(); currentStore = null
        workingFiles().forEach(TranscriptStore::deleteArtifacts)
        historyOwners.close()
    }

    private fun showError(error: Exception) {
        if (error is CancellationException) throw error
        Log.e("TranscribeDialog", "Local transcription operation failed", error)
        val message = StorageFailure.userMessage(app, error)
            ?: error.message ?: app.getString(R.string.transcribe_error_failed)
        mutable.update { it.copy(message = message) }
        ActionFeedback.show(app, message)
    }
    fun saveInstanceState(out: android.os.Bundle) {
        out.putString("owned_audio", ownedAudioId)
        out.putString("working_text", currentStore?.file?.absolutePath)
        out.putString("saved_text", state.value.transcriptId)
        out.putBoolean("text_origin", input.transcriptOrigin)
        out.putString("result_model", chosenModel)
        out.putString("result_model_id", resultModelId)
        resultMetadata.writeToBundle(out)
    }
    override fun onCleared() {
        // Unexpected owner destruction preserves disk-backed input and useful
        // partial text. Explicit dismiss already deleted/released its own work.
        currentStore?.keepForRecovery()
        exportPreparation?.cancel()
        app.applicationScope.launch(Dispatchers.IO) { releaseExport { audioExportSession.close() }; historyOwners.close() }
        app.audioPlayback.stop(playbackOwner)
        super.onCleared()
    }
}
