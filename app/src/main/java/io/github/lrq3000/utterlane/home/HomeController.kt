package io.github.lrq3000.utterlane.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.history.TranscriptMetadata
import io.github.lrq3000.utterlane.transcribe.*
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class HomeState(
    val capture: HomeCaptureState = HomeCaptureState(),
    val metrics: CaptureSnapshot = CaptureSnapshot(),
    val result: TranscriptionDialogState = TranscriptionDialogState(),
    val model: TranscriptionDialogModel? = null,
    val preparing: Boolean = false,
    val permissionDenied: Boolean = false,
    val message: String? = null
) {
    val busy get() = capture.active || preparing || result.running || result.importing || result.saving || result.closing || result.deleting || result.checkingDeletion
    val canStop get() = capture.phase in setOf(HomeCapturePhase.STARTING, HomeCapturePhase.RECORDING, HomeCapturePhase.STOPPING)

    // Loading/retrying owned audio never opens the microphone. Scope a denial to
    // the earlier capture attempt, so later file errors retain Retry/model actions.
    fun prepareFileOperation(): HomeState = copy(preparing = true, message = null, permissionDenied = false)
}

/**
 * Application-owned workspace: Activities only observe it. One bounded preview,
 * one capture, and one visible result owner survive navigation and recreation.
 * At most one file candidate waits for owned input before replacing that result.
 * The engine, source copy, retries and saves remain shared with
 * external share/open transcription rather than creating a second pipeline.
 */
class HomeController(private val app: UtterlaneApp) {
    private val mutable = MutableStateFlow(HomeState())
    internal val state: StateFlow<HomeState> = mutable
    private val scope get() = app.applicationScope
    // The live microphone has no dialog model yet; it uses the same bounded
    // document implementation until the result model takes ownership.
    internal val liveDocument = TranscriptPager(scope)
    private var captureDriver: CaptureDriver? = null
    // Only bounded in-memory ownership/journal publication is protected here.
    // Never acquire a history/source lock or wait for IO while holding this gate.
    private val ownerGate = Any()
    @Volatile private var slot: ResultOwner? = null
    @Volatile private var fileCandidate: ResultOwner? = null
    private var pendingFile: Uri? = null
    private var pendingRetry = false
    private val service = HomeServiceConnection()
    private val journal = HomeJournal(app)
    private val captureOwner = HomeCaptureOwner<CaptureResult>(
        factory = { events -> CaptureDriver(events).also { captureDriver = it } },
        onAccepted = {
            releaseResult()
            mutable.update { it.copy(result = TranscriptionDialogState(), message = null, permissionDenied = false) }
        }, onResult = ::adoptCapture)

    init {
        scope.launch { captureOwner.state.collect { capture ->
            mutable.update { it.copy(capture = capture, message = capture.message ?: it.message) }
        } }
        journal.restore()?.let { installResult(it) }
    }

    /** Called only from a visible Activity after RECORD_AUDIO permission is granted. */
    fun record() {
        if (captureOwner.state.value.active) { captureOwner.stop(); return }
        if (state.value.busy) return
        captureOwner.start()
    }

    fun load(uri: Uri) {
        if (captureOwner.state.value.active || state.value.busy) return
        pendingFile = uri
        mutable.update { it.prepareFileOperation() }
        try { requestService(microphone = false) }
        catch (error: Exception) {
            pendingFile = null
            mutable.update { it.copy(preparing = false) }
            showError(error)
        }
    }

    fun retry() {
        if (state.value.busy || slot == null) return
        pendingRetry = true
        mutable.update { it.prepareFileOperation() }
        try { requestService(microphone = false) }
        catch (error: Exception) { pendingRetry = false; mutable.update { it.copy(preparing = false) }; showError(error) }
    }

    fun showMessage(message: String) { mutable.update { it.copy(message = message) } }
    fun microphoneDenied() {
        mutable.update { it.copy(permissionDenied = true, message = app.getString(R.string.onboarding_permission_denied)) }
    }

    private fun requestService(microphone: Boolean) {
        val token = UUID.randomUUID().toString()
        service.request(token)
        try {
            ContextCompat.startForegroundService(app, Intent(app, HomeSessionService::class.java)
                .putExtra(HomeSessionService.TOKEN, token).putExtra(HomeSessionService.MICROPHONE, microphone))
        } catch (error: Exception) { service.detached(token); throw error }
    }

    internal fun ownsService(token: String?) = service.owns(token)

    internal fun serviceStopping(token: String) { service.stopping(token) }

    internal fun serviceStarted(token: String) {
        if (!ownsService(token)) return
        service.started(token)
        when {
            pendingFile != null -> {
                val uri = pendingFile!!; pendingFile = null
                // Picking a URI is only an attempt. Retain the old owner/journal
                // until this candidate has actually copied nonempty private input.
                val candidate = ResultOwner(DialogInput(uri = uri, automatic = true), null)
                fileCandidate = candidate
                candidate.observer = HomeFileHandoff(scope, app.recordingHistory, app.transcriptHistory).observe(candidate.model.state,
                    isCurrent = { fileCandidate === candidate && !candidate.retiring },
                    onAccepted = { acceptFile(candidate) },
                    onRejected = { rejectFile(candidate, it ?: app.getString(R.string.transcribe_error_failed)) })
            }
            pendingRetry -> {
                pendingRetry = false
                val model = slot?.model
                HomeRetryHandoff.run(mutable, retry = { model?.retry(useCurrentModel = true) },
                    snapshot = { model?.state?.value ?: mutable.value.result })
            }
            // A direct result retry attaches a processing service without a
            // pendingRetry flag. It is never a new request to open the microphone.
            else -> if (captureOwner.state.value.active) captureDriver?.begin()
        }
    }

    internal fun stopCapture(token: String) { if (ownsService(token)) captureOwner.stop() }

    internal fun serviceFailed(token: String, error: Exception) {
        if (!ownsService(token)) return
        service.detached(token)
        pendingFile = null; pendingRetry = false
        captureDriver?.reject(error.message ?: app.getString(R.string.toast_recording_error))
        val candidate = fileCandidate
        if (candidate != null) rejectFile(candidate, error.message ?: app.getString(R.string.transcribe_error_failed))
        else mutable.update { it.copy(preparing = false) }
        showError(error)
    }

    internal fun serviceDestroyed(token: String, expected: Boolean) {
        if (!ownsService(token)) return
        service.detached(token)
        if (!expected) {
            // Actual service loss must stop microphone ownership, but it is not
            // permission to discard input. MicrophoneSession writes recovery.
            captureOwner.interrupt()
            if (pendingFile != null || fileCandidate != null) {
                pendingFile = null
                val message = app.getString(R.string.stream_cancelled)
                fileCandidate?.let { rejectFile(it, message) }
                    ?: mutable.update { it.copy(preparing = false, message = message) }
            }
            // The application still owns any prior result. Service destruction
            // must not release its text lease while a new empty attempt closes.
        }
    }

    private data class CaptureResult(val driver: CaptureDriver, val store: TranscriptStore?, val failure: SessionFailure?)

    private inner class CaptureDriver(private val events: HomeCaptureOwner.Events<CaptureResult>) : HomeCaptureOwner.Driver {
        private var session: MicrophoneSession? = null
        private var observer: Job? = null
        private var spoolObserver: Job? = null
        private var ended = false
        private var delivered = false
        private var sourceLease: Closeable? = null
        private var rawStore: TranscriptStore? = null
        private var inputAccepted = false
        private val spool = HomeCaptureSpool(app.recordingHistory)
        private val completion = HomeCaptureCompletion<CaptureResult>(events::result, ::reject)

        override fun start() { requestService(microphone = true) }
        fun begin() {
            if (session != null || ended) return
            val created = app.microphoneSessions.create(app, scope,
                onText = { _, store -> publishInput(store = store) }, onComplete = { store, failure ->
                    delivered = true
                    if (failure?.kind == SessionFailure.Kind.BUSY) reject(failure.message)
                    else deliverResult(store, failure)
                }, onCaptureEnded = { events.captureEnded() },
                onWarning = { if (captureDriver === this) showMessage(it) },
                onReady = {
                    if (isCurrent()) {
                        events.ready()
                        // Lease this attempt while its session holds the writer
                        // lease, but do not replace the prior recovery descriptor.
                        // Readiness and even captured metrics can precede PCM IO.
                        val id = session?.audioId
                        try { if (id != null && sourceLease == null) sourceLease = app.recordingHistory.acquire(id) }
                        catch (error: Exception) { Log.w("Home", "Could not lease source audio", error) }
                    }
                }, onSessionClosed = {
                    observer?.cancel()
                    spoolObserver?.cancel()
                    if (!delivered) {
                        // Cancellation preserves input but omits onComplete. Adopt
                        // its partial workspace too, so explicit Next can later
                        // acknowledge it instead of leaking temporary ownership.
                        deliverResult(rawStore, SessionFailure(SessionFailure.Kind.AUDIO, app.getString(R.string.stream_cancelled)))
                    }
                    ended = true
                    events.closed()
                    if (captureDriver === this) captureDriver = null
                    // A cancelled session does not deliver onComplete. Leave its
                    // journal and disk-backed data available for recovery.
                    sourceLease?.let { lease -> scope.launch(Dispatchers.IO) { lease.close() } }; sourceLease = null
                }, keepResultAudio = true, openRecoveryOnFailure = false)
            session = created
            observer = scope.launch { created.metrics.state.collect { metrics ->
                if (!isCurrent()) return@collect
                mutable.update { it.copy(metrics = metrics) }
            } }
            spoolObserver = scope.launch {
                // Metrics can be conflated after the producer pauses. Poll the
                // writer independently until the first committed sample, with
                // one constant-size IO probe at a time and no permanent extra job.
                while (isActive && isCurrent() && !inputAccepted) {
                    val id = created.audioId
                    if (id != null && created.metrics.state.value.capturedSamples > 0) {
                        val published = withContext(Dispatchers.IO) { spool.hasPublishedSamples(id) }
                        if (published) publishInput(spooledSamples = 1)
                    }
                    if (!inputAccepted) delay(created.metrics.visualRefreshIntervalMillis())
                }
            }
            created.start()
        }
        override fun stop() { session?.stop() }
        private fun isCurrent() = captureDriver === this && !ended

        private fun publishInput(spooledSamples: Long = 0, store: TranscriptStore? = rawStore) {
            if (!isCurrent()) return
            val preview = store?.preview().orEmpty()
            if (!events.input(spooledSamples, preview)) return
            inputAccepted = true
            rawStore = store
            if (store != null) {
                if (state.value.result.store !== store) liveDocument.show(store) else liveDocument.refresh()
                mutable.update { it.copy(result = it.result.copy(store = store, preview = preview, transcriptBytes = store.bytes)) }
            }
            // Acceptance synchronously retires the old owner before this new
            // checkpoint is published. Neither empty Ready nor stale IO can erase
            // the previous descriptor; final completion remains a fallback.
            synchronized(ownerGate) {
                journal.capture(session?.audioId, store, session?.metrics?.state?.value,
                    speakerLabels = session?.speakerLabels == true)
            }
        }
        private fun deliverResult(store: TranscriptStore?, failure: SessionFailure?) {
            val audio = session?.audioId?.let { runCatching { app.recordingHistory.get(it) }.getOrNull() }
            if (!completion.deliver(CaptureResult(this, store, failure), audio, store?.preview().orEmpty(),
                    failure?.message ?: app.getString(R.string.toast_recording_error))) {
                // Model preparation can create an empty text store even when the
                // recorder never opens. No result owner will consume that store.
                store?.let { scope.launch(Dispatchers.IO) { it.dispose() } }
            }
        }
        override fun interrupt() {
            if (session == null) { ended = true; events.closed(); captureDriver = null }
            else session?.cancel(discard = false)
        }
        fun reject(message: String) {
            ended = true; observer?.cancel(); spoolObserver?.cancel(); events.rejected(message)
            if (captureDriver === this) captureDriver = null
        }
        fun input(store: TranscriptStore?, metadata: TranscriptMetadata) = DialogInput(
            audioId = session?.audioId, transcriptId = session?.savedTranscriptId,
            transcriptPath = store?.file?.absolutePath, transcriptOrigin = false,
            modelName = session?.metrics?.state?.value?.modelName.orEmpty(),
            modelId = app.modelManager.selected.value.id, metadata = metadata)
        fun audioId() = session?.audioId
        fun takeLease(): Closeable? = sourceLease.also { sourceLease = null }
        fun snapshot() = session?.metrics?.state?.value ?: CaptureSnapshot()
        fun speakerLabels() = session?.speakerLabels == true
    }

    private fun adoptCapture(result: CaptureResult) {
        val lease = result.driver.takeLease()
        mutable.update { it.copy(preparing = true, message = result.failure?.message) }
        scope.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    result.driver.audioId()?.let { runCatching { TranscriptMetadata(app.recordingHistory.get(it)) }.getOrNull() }
                        ?: TranscriptMetadata(durationMs = (result.driver.snapshot().capturedSeconds * 1000).toLong(),
                            speakerLabels = result.driver.speakerLabels())
                }
                val input = result.driver.input(result.store, metadata)
                synchronized(ownerGate) { journal.write(input) }
                installResult(input, lease)
                // The new model acquires its own text owner asynchronously. Keep
                // the file for recovery instead of scheduling deletion underneath it.
                result.store?.keepForRecovery()
            } catch (error: Exception) { lease?.let { scope.launch(Dispatchers.IO) { it.close() } }; showError(error)
            } finally { mutable.update { it.copy(preparing = false) } }
        }
    }

    private fun installResult(input: DialogInput, lease: Closeable? = null) {
        liveDocument.show(null)
        val owner = ResultOwner(input, lease)
        slot = owner
        mutable.update { it.copy(model = owner.model, result = owner.model.state.value) }
        owner.observe()
    }

    private fun acceptFile(candidate: ResultOwner) {
        // Called on IO inside authoritative source/history guards. This snapshot
        // is bounded model metadata; acquire no further repository locks here.
        val checkpoint = candidate.prepareCheckpoint(candidate.model.state.value)
        val previous = synchronized(ownerGate) {
            if (fileCandidate !== candidate || candidate.retiring) return
            journal.write(checkpoint)
            val previous = slot
            previous?.observer?.cancel()
            slot = candidate
            fileCandidate = null
            liveDocument.show(null)
            // Logical retirement, journal replacement and current model state
            // become visible before deletion can acquire the source guard.
            HomeFileHandoff.publish(mutable, candidate.model, candidate.model.state)
            previous
        }
        scope.launch {
            if (slot === candidate) candidate.observe()
            previous?.let { retireResult(it) }
        }
    }

    private fun rejectFile(candidate: ResultOwner, message: String) {
        synchronized(ownerGate) {
            if (fileCandidate !== candidate || candidate.retiring) return
            candidate.retiring = true
            candidate.observer?.cancel()
            mutable.update { it.copy(message = message) }
        }
        // Keep the bounded pending slot/preparation hold until cancellation and
        // cleanup finish. A second attempt cannot accumulate retiring candidates.
        retireResult(candidate) {
            synchronized(ownerGate) {
                if (fileCandidate === candidate) {
                    fileCandidate = null
                    mutable.update { it.copy(preparing = false) }
                }
            }
        }
    }

    /** Explicit Next/Dismiss only. Navigation and Activity destruction never call this. */
    fun dismiss() {
        slot?.let(::dismissOwner)
    }

    private fun dismissOwner(owner: ResultOwner) {
        if (state.value.capture.active || state.value.preparing) return
        val done = {
            synchronized(ownerGate) {
                if (slot === owner) {
                    slot = null; journal.clear()
                    mutable.update { HomeState() }
                }
            }
            owner.clear()
        }
        // Confirmed deletion already disposed exactly the selected identities.
        // Do not run another dismissal against surviving independent history.
        if (owner.model.state.value.finished) done() else owner.model.dismiss(done)
    }

    private fun releaseResult() {
        val previous = synchronized(ownerGate) {
            val previous = slot ?: return
            slot = null
            previous.observer?.cancel()
            journal.clear()
            mutable.update { it.copy(model = null) }
            previous
        }
        retireResult(previous)
    }

    private fun retireResult(owner: ResultOwner, onRetired: () -> Unit = {}) {
        owner.observer?.cancel()
        var retired = false
        val finish = {
            if (!retired) {
                retired = true
                owner.clear()
                onRetired()
            }
        }
        owner.model.dismiss(finish)
        // Dismissal can fail on storage. Its model reports that failure rather
        // than calling done. Release runtime owners in that case too, preserving
        // disk recovery instead of accumulating invisible ViewModel collectors.
        if (!retired) owner.observer = scope.launch {
            val failed = owner.model.state.first { !it.closing }
            failed.message?.let(::showMessage)
            finish()
        }
    }

    private inner class ResultOwner(val input: DialogInput, lease: Closeable?) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TranscriptionDialogModel(app, input) as T
        })[TranscriptionDialogModel::class.java]
        var observer: Job? = null
        @Volatile var retiring = false
        private var audioLease: Closeable? = lease
        private var leasedId: String? = if (lease != null) input.audioId else null
        private val descriptor = HomeResultDescriptor(input)
        fun observe() {
            observer = HomeResultObserver(scope).observe(mutable, model.state,
                isCurrent = { slot === this@ResultOwner }, onStarted = {
                // A retry launched by the reused detailed dialog also receives a
                // permission-free processing FGS, just like Home's Retry action.
                if (service.needsStart && !state.value.capture.active) {
                    try { requestService(microphone = false) } catch (error: Exception) { showError(error) }
                }
            }) { result, _ ->
                if (result.finished && !result.deleting) {
                    dismissOwner(this@ResultOwner)
                    return@observe
                }
                checkpoint(result)
            }
        }
        fun checkpoint(result: TranscriptionDialogState) {
            if (!result.importing && result.audio?.id != leasedId) {
                audioLease?.let { lease -> scope.launch(Dispatchers.IO) { lease.close() } }
                leasedId = result.audio?.id
                audioLease = leasedId?.let { runCatching { app.recordingHistory.acquire(it) }.getOrNull() }
            }
            val snapshot = prepareCheckpoint(result)
            synchronized(ownerGate) {
                // A prior collector may already have been in flight when IO
                // promoted the candidate. It must never restore the old journal.
                if (slot === this) journal.write(snapshot)
            }
        }
        fun prepareCheckpoint(result: TranscriptionDialogState): DialogInput {
            val saved = Bundle().also(model::saveInstanceState)
            return descriptor.update(input.copy(uri = null, path = null, automatic = false,
                audioId = saved.getString("owned_audio"), transcriptId = saved.getString("saved_text"),
                transcriptPath = saved.getString("working_text"), modelName = saved.getString("result_model").orEmpty(),
                // The model owns result metadata independently of source
                // history revisions, retention and other model attempts.
                modelId = saved.getString("result_model_id"), metadata = model.metadata), importing = result.importing)
        }
        fun clear() {
            observer?.cancel()
            audioLease?.let { lease -> scope.launch(Dispatchers.IO) { lease.close() } }
            audioLease = null
            viewModelStore.clear()
        }
    }

    private fun showError(error: Exception) {
        Log.e("Home", "Home operation failed", error)
        showMessage(error.message ?: app.getString(R.string.transcribe_error_failed))
    }
}
