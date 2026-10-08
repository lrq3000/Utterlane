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
    val busy get() = capture.active || preparing || result.running || result.importing || result.saving || result.closing
    val canStop get() = capture.phase in setOf(HomeCapturePhase.STARTING, HomeCapturePhase.RECORDING, HomeCapturePhase.STOPPING)
}

/**
 * Application-owned workspace: Activities only observe it. One bounded preview,
 * one capture, and one explicitly owned dialog ViewModelStore survive navigation
 * and recreation. The engine, source copy, retries and saves remain shared with
 * external share/open transcription rather than creating a second pipeline.
 */
class HomeController(private val app: UtterlaneApp) {
    private val mutable = MutableStateFlow(HomeState())
    internal val state: StateFlow<HomeState> = mutable
    private val scope get() = app.applicationScope
    private var captureDriver: CaptureDriver? = null
    private var slot: ResultOwner? = null
    private var pendingFile: Uri? = null
    private var pendingRetry = false
    private var serviceToken: String? = null
    private var serviceAttached = false
    private var servicePending = false
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
        mutable.update { it.copy(preparing = true, message = null) }
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
        mutable.update { it.copy(preparing = true, message = null) }
        try { requestService(microphone = false) }
        catch (error: Exception) { pendingRetry = false; mutable.update { it.copy(preparing = false) }; showError(error) }
    }

    fun showMessage(message: String) { mutable.update { it.copy(message = message) } }
    fun microphoneDenied() {
        mutable.update { it.copy(permissionDenied = true, message = app.getString(R.string.onboarding_permission_denied)) }
    }

    private fun requestService(microphone: Boolean) {
        val token = UUID.randomUUID().toString()
        serviceToken = token
        servicePending = true
        try {
            ContextCompat.startForegroundService(app, Intent(app, HomeSessionService::class.java)
                .putExtra(HomeSessionService.TOKEN, token).putExtra(HomeSessionService.MICROPHONE, microphone))
        } catch (error: Exception) { servicePending = false; throw error }
    }

    internal fun ownsService(token: String?) = token != null && token == serviceToken

    internal fun serviceStarted(token: String) {
        if (!ownsService(token)) return
        servicePending = false
        serviceAttached = true
        when {
            pendingFile != null -> {
                val uri = pendingFile!!; pendingFile = null
                // The picker result is now deliberately replacing the old work.
                // Document providers are copied by DialogAudioActions on IO.
                releaseResult()
                installResult(DialogInput(uri = uri, automatic = true))
                mutable.update { it.copy(preparing = false) }
            }
            pendingRetry -> {
                pendingRetry = false
                val model = slot?.model
                HomeRetryHandoff.run(mutable, retry = { model?.retry(useCurrentModel = true) },
                    snapshot = { model?.state?.value ?: mutable.value.result })
            }
            else -> captureDriver?.begin()
        }
    }

    internal fun stopCapture(token: String) { if (ownsService(token)) captureOwner.stop() }

    internal fun serviceFailed(token: String, error: Exception) {
        if (!ownsService(token)) return
        servicePending = false
        pendingFile = null; pendingRetry = false
        captureDriver?.reject(error.message ?: app.getString(R.string.toast_recording_error))
        mutable.update { it.copy(preparing = false) }
        showError(error)
    }

    internal fun serviceDestroyed(token: String, expected: Boolean) {
        if (!ownsService(token)) return
        serviceAttached = false
        if (!expected) {
            // Actual service loss must stop microphone ownership, but it is not
            // permission to discard input. MicrophoneSession writes recovery.
            captureOwner.interrupt()
            slot?.preserve()
        } else if (state.value.result.running && !servicePending) {
            // A detail-dialog Retry can arrive between stopSelf and onDestroy.
            // Reestablish foreground ownership for that new user-requested work.
            try { requestService(microphone = false) } catch (error: Exception) { showError(error) }
        }
    }

    private data class CaptureResult(val driver: CaptureDriver, val store: TranscriptStore?, val failure: SessionFailure?)

    private inner class CaptureDriver(private val events: HomeCaptureOwner.Events<CaptureResult>) : HomeCaptureOwner.Driver {
        private var session: MicrophoneSession? = null
        private var observer: Job? = null
        private var ended = false
        private var delivered = false
        private var sourceLease: Closeable? = null
        private var rawStore: TranscriptStore? = null

        override fun start() { requestService(microphone = true) }
        fun begin() {
            if (session != null || ended) return
            val created = app.microphoneSessions.create(app, scope,
                onText = { _, store ->
                    if (captureDriver === this && !ended) {
                        rawStore = store
                        mutable.update { it.copy(result = it.result.copy(store = store, preview = store.preview())) }
                        journal.capture(session?.audioId, store, session?.metrics?.state?.value)
                    }
                }, onComplete = { store, failure ->
                    delivered = true
                    if (failure?.kind == SessionFailure.Kind.BUSY || (session?.audioId == null && store == null)) {
                        reject(failure?.message ?: app.getString(R.string.toast_recording_error))
                    } else events.result(CaptureResult(this, store, failure))
                }, onCaptureEnded = { events.captureEnded() },
                onWarning = { if (captureDriver === this) showMessage(it) },
                onReady = {
                    events.ready()
                    // Acquire while the session still holds its writer/read lease.
                    // A scheduled prune cannot physically remove an open source.
                    val id = session?.audioId
                    // acquire is an O(1) in-memory index operation. Acquiring
                    // synchronously prevents close-before-acquire lease leaks.
                    try { if (id != null) sourceLease = app.recordingHistory.acquire(id) }
                    catch (error: Exception) { Log.w("Home", "Could not lease source audio", error) }
                    journal.capture(id, rawStore, session?.metrics?.state?.value)
                }, onSessionClosed = {
                    observer?.cancel()
                    if (!delivered && session?.audioId != null) {
                        // Cancellation preserves input but omits onComplete. Adopt
                        // its partial workspace too, so explicit Next can later
                        // acknowledge it instead of leaking temporary ownership.
                        events.result(CaptureResult(this, rawStore,
                            SessionFailure(SessionFailure.Kind.AUDIO, app.getString(R.string.stream_cancelled))))
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
                if (captureDriver === this@CaptureDriver) mutable.update { it.copy(metrics = metrics) }
            } }
            created.start()
        }
        override fun stop() { session?.stop() }
        override fun interrupt() {
            if (session == null) { ended = true; events.closed(); captureDriver = null }
            else session?.cancel(discard = false)
        }
        fun reject(message: String) {
            ended = true; observer?.cancel(); events.rejected(message)
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
                journal.write(input)
                installResult(input, lease)
                // The new model acquires its own text owner asynchronously. Keep
                // the file for recovery instead of scheduling deletion underneath it.
                result.store?.keepForRecovery()
            } catch (error: Exception) { lease?.let { scope.launch(Dispatchers.IO) { it.close() } }; showError(error)
            } finally { mutable.update { it.copy(preparing = false) } }
        }
    }

    private fun installResult(input: DialogInput, lease: Closeable? = null) {
        val owner = ResultOwner(input, lease)
        slot = owner
        mutable.update { it.copy(model = owner.model, result = owner.model.state.value) }
        owner.observe()
    }

    /** Explicit Next/Dismiss only. Navigation and Activity destruction never call this. */
    fun dismiss(delete: Boolean = false) {
        if (state.value.capture.active || state.value.preparing) return
        val owner = slot ?: return
        owner.model.dismiss(delete) {
            if (slot === owner) {
                slot = null; journal.clear()
                mutable.update { HomeState() }
            }
            owner.clear()
        }
    }

    private fun releaseResult() {
        val previous = slot ?: return
        slot = null
        previous.observer?.cancel()
        journal.clear()
        mutable.update { it.copy(model = null) }
        previous.model.dismiss { previous.clear() }
        // Dismissal can fail on storage. Its model reports that failure rather
        // than calling done. Release runtime owners in that case too, preserving
        // disk recovery instead of accumulating invisible ViewModel collectors.
        previous.observer = scope.launch {
            val failed = previous.model.state.first { !it.closing }
            failed.message?.let(::showMessage)
            previous.clear()
        }
    }

    private inner class ResultOwner(val input: DialogInput, lease: Closeable?) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TranscriptionDialogModel(app, input) as T
        })[TranscriptionDialogModel::class.java]
        var observer: Job? = null
        private var audioLease: Closeable? = lease
        private var leasedId: String? = if (lease != null) input.audioId else null
        private var metadata = input.metadata ?: TranscriptMetadata()
        private var wasRunning = false
        private var previousStore: TranscriptStore? = null
        private val descriptor = HomeResultDescriptor(input)
        fun observe() {
            observer = scope.launch { model.state.collect { result ->
                if (slot !== this@ResultOwner) return@collect
                mutable.update { it.copy(result = result) }
                // A retry launched by the reused detailed dialog also receives a
                // permission-free processing FGS, just like Home's Retry action.
                if (result.running && !serviceAttached && !servicePending && !state.value.capture.active) {
                    try { requestService(microphone = false) } catch (error: Exception) { showError(error) }
                }
                if (!result.importing && result.audio?.id != leasedId) {
                    audioLease?.let { lease -> scope.launch(Dispatchers.IO) { lease.close() } }
                    leasedId = result.audio?.id
                    audioLease = leasedId?.let { runCatching { app.recordingHistory.acquire(it) }.getOrNull() }
                }
                if (metadata.created == null) result.audio?.let { metadata = TranscriptMetadata(it) }
                if (wasRunning && !result.running && result.store !== previousStore) {
                    // Snapshot only our completed retry, not arbitrary later audio
                    // history revisions. Text metadata must survive source deletion
                    // and unrelated retranscriptions of the same source recording.
                    val savedText = result.transcriptId?.let { runCatching { app.transcriptHistory.get(it) }.getOrNull() }
                    val audio = leasedId?.let { runCatching { app.recordingHistory.get(it) }.getOrNull() }
                    metadata = savedText?.let(::TranscriptMetadata) ?: audio?.let(::TranscriptMetadata) ?: metadata
                }
                if (!result.running) previousStore = result.store
                wasRunning = result.running
                val saved = Bundle().also(model::saveInstanceState)
                journal.write(descriptor.update(input.copy(uri = null, path = null, automatic = false,
                    audioId = saved.getString("owned_audio"), transcriptId = saved.getString("saved_text"),
                    transcriptPath = saved.getString("working_text"), modelName = saved.getString("result_model").orEmpty(),
                    modelId = saved.getString("result_model_id"), metadata = metadata), importing = result.importing))
            } }
        }
        fun preserve() { model.state.value.store?.keepForRecovery() }
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
