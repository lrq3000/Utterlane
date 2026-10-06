package io.github.lrq3000.utterlane.asr

import android.content.Context
import android.util.Log
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Serialized model operations with a separate, nonblocking reset/ownership boundary. */
class RecognizerManager(
    private val context: Context,
    private val modelManager: ModelManager,
    elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val backendFactory: (ModelDefinition, String) -> RecognitionBackend = { model, _ -> WorkerRecognitionBackend(context, model) }
) {
    companion object { private const val TAG = "RecognizerManager" }
    private val mutex = Mutex()
    private val sessionPreparation = RecognitionSessionPreparation(mutex) {
        UtterlaneApp.instance.settingsRepository.runtimeOptions.first()
    }
    private val stateLock = Any()
    private var recognizer: RecognitionBackend? = null
    private var loadedModelId: String? = null
    private val runtimeConfiguration = RecognitionRuntimeConfiguration()
    private var generation = 0L
    private val sessionIds = AtomicLong(0)
    private val sessions = mutableMapOf<Long, Job?>()
    private val idlePolicy = ModelIdlePolicy(elapsedMillis)
    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null
    private var idleRevision = 0L
    private var pendingOperations = 0
    // DataStore loads asynchronously. Do not apply the default over a persisted
    // Never/Immediate choice while its first application-scoped emission is pending.
    private var idlePolicyConfigured = false
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure
    private val _activity = MutableStateFlow(RecognitionActivity())
    val activity: StateFlow<RecognitionActivity> = _activity
    @Volatile private var activityListener: (RecognitionActivity, RuntimeOptions, RuntimeOptions) -> Unit = { _, _, _ -> }
    // Shared worker provenance follows configure, while consent follows the caller of
    // each serialized request. KEEP must not freeze a later session's opt-in at load time.
    private var workerActivityOptions = RuntimeOptions()
    private val activityOwnership = RecognitionActivityOwnership()
    fun setActivityListener(listener: (RecognitionActivity, RuntimeOptions, RuntimeOptions) -> Unit) { activityListener = listener }

    suspend fun initialize(): Boolean = withModelOperation { expected ->
        withContext(Dispatchers.IO) {
            val options = UtterlaneApp.instance.settingsRepository.runtimeOptions.first().requireValid()
            mutex.withLock { initializeLocked(expected, options) }
        }
    }

    /** Reserve before waiting for the inference mutex, including session creation. */
    private suspend fun <T> withModelOperation(block: suspend (Long) -> T): T {
        val expected = synchronized(stateLock) {
            pendingOperations++
            refreshIdleTimerLocked()
            generation
        }
        return try { block(expected) }
        finally {
            synchronized(stateLock) {
                pendingOperations--
                refreshIdleTimerLocked()
            }
        }
    }

    fun setIdleTimeout(timeout: ModelIdleTimeout) = synchronized(stateLock) {
        idlePolicy.timeout = timeout
        idlePolicyConfigured = true
        refreshIdleTimerLocked()
    }

    /** Coroutine delays may pause during deep sleep; re-evaluate elapsed time on wake. */
    fun recheckIdleTimeout() = synchronized(stateLock) { refreshIdleTimerLocked() }

    private fun cancelIdleTimerLocked() {
        idleRevision++
        idleJob?.cancel()
        idleJob = null
    }

    private fun refreshIdleTimerLocked() {
        cancelIdleTimerLocked()
        idlePolicy.setIdle(recognizer != null && !_isLoading.value && pendingOperations == 0 && sessions.isEmpty())
        if (!idlePolicyConfigured) return
        val remaining = idlePolicy.remainingMillis() ?: return
        val revision = idleRevision
        idleJob = idleScope.launch {
            delay(remaining)
            mutex.withLock {
                val backend = synchronized(stateLock) {
                    // Cancellation alone is insufficient: an old callback may
                    // already be waiting on the mutex when new work starts.
                    if (revision != idleRevision || pendingOperations != 0 || sessions.isNotEmpty() || _isLoading.value) return@withLock
                    if (idlePolicy.remainingMillis() != 0L) {
                        refreshIdleTimerLocked()
                        return@withLock
                    }
                    idleJob = null // Detaching must not cancel its own close operation.
                    detachLocked(clearFailure = false)
                }
                // Keep close inside the inference mutex: a replacement binds the
                // same worker service, so it must not start before the old PID exits.
                backend?.close()
                Log.i(TAG, "Recognition model unloaded after inactivity")
            }
        }
    }

    private suspend fun initializeLocked(expected: Long, options: RuntimeOptions): Boolean {
        synchronized(stateLock) { if (expected != generation) return false }
        modelManager.initializeSelection()
        val model = modelManager.selected.value
        synchronized(stateLock) {
            if (expected != generation) return false
            if (_isReady.value && loadedModelId == model.id && recognizer?.isAvailable() == true) {
                when (runtimeConfiguration.change(options, sessions.isNotEmpty())) {
                    RecognitionRuntimeConfiguration.Change.KEEP -> return true
                    RecognitionRuntimeConfiguration.Change.CONFIGURE -> {
                        // No active session may have its recovery budget changed by
                        // another caller. The inference mutex also excludes live JNI.
                        checkNotNull(recognizer).configure(options)
                        workerActivityOptions = options
                        runtimeConfiguration.applied(options)
                        return true
                    }
                    RecognitionRuntimeConfiguration.Change.RELOAD -> Unit
                }
            }
            _isLoading.value = true
            _failure.value = null
        }
        var candidate: RecognitionBackend? = null
        var activityOperation: RecognitionActivityOwnership.Operation? = null
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            check(modelManager.ensureVerified()) { context.getString(R.string.model_error_checksum) }
            val previous = synchronized(stateLock) {
                if (expected != generation) return false
                check(sessions.isEmpty()) { "A transcription session is active" }
                val old = recognizer
                recognizer = null; loadedModelId = null; _isReady.value = false
                _activity.value = RecognitionActivity()
                old
            }
            previous?.close()
            candidate = backendFactory(model, modelManager.getModelPath())
            val ownedCandidate = candidate
            candidate.setFailureListener { message ->
                synchronized(stateLock) {
                    if (expected == generation && recognizer === ownedCandidate) {
                        _isReady.value = false
                        _failure.value = message
                        _activity.value = _activity.value.copy(active = false, message = message)
                    }
                }
            }
            candidate.setActivityListener { activity ->
                val observedOptions = synchronized(stateLock) {
                    // A request number is only unique within its worker. Both
                    // generation and backend identity guard a replacement's status.
                    if (expected == generation && recognizer === ownedCandidate) {
                        _activity.value = activity
                        activityOwnership.snapshot()
                    } else null
                }
                // Nonblocking content-free handoff, outside the manager ownership lock.
                if (observedOptions != null) activityListener(activity, observedOptions.operationOptions, observedOptions.workerOptions)
            }
            candidate.configure(options)
            val published = synchronized(stateLock) {
                if (expected != generation) false else {
                    // Publish before preparation: reset can abort a blocked worker load.
                    recognizer = candidate
                    workerActivityOptions = options
                    activityOperation = activityOwnership.begin(options, options)
                    true
                }
            }
            // close can deliver activity. Never call it while holding stateLock:
            // a concurrent observer may be waiting for this lock on its own monitor.
            if (!published) { candidate.close(); return false }
            runInterruptible(Dispatchers.IO) { candidate.prepare() }
            currentCoroutineContext().ensureActive()
            val ready = synchronized(stateLock) {
                if (expected != generation) false else {
                    check(candidate.isAvailable()) { "Recognition worker exited during initialization" }
                    loadedModelId = model.id
                    runtimeConfiguration.applied(options)
                    _isReady.value = true
                    true
                }
            }
            if (!ready) { candidate.close(); return false }
            Log.i(TAG, "Recognizer validated: ${model.id}, elapsed=${android.os.SystemClock.elapsedRealtime() - start}ms")
            true
        } catch (e: CancellationException) {
            candidate?.close()
            synchronized(stateLock) {
                if (expected == generation && candidate != null && recognizer === candidate) {
                    recognizer = null; loadedModelId = null; _isReady.value = false
                    _activity.value = RecognitionActivity()
                }
            }
            throw e
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError && error !is OutOfMemoryError) throw error
            candidate?.close()
            Log.e(TAG, "Failed to load ${model.id}", error)
            synchronized(stateLock) {
                if (expected == generation) {
                    // Preconditions can fail before candidate replacement. Never
                    // orphan the existing worker still owned by a draining session.
                    if (candidate != null && recognizer === candidate) {
                        recognizer = null; loadedModelId = null; _isReady.value = false
                        _activity.value = RecognitionActivity(message = error.message)
                    }
                    _failure.value = "${model.name}: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            false
        } finally {
            synchronized(stateLock) {
                activityOperation?.let(activityOwnership::finish)
                if (expected == generation) _isLoading.value = false
            }
        }
    }

    suspend fun ensureInitialized(): Boolean = initialize()
    fun isInitialized(): Boolean = _isReady.value

    // Retain the original positional/default/trailing-lambda API. Both overloads enter
    // one reservation/cleanup path; only callers without a snapshot read preferences.
    suspend fun createSession(onProcessed: (Long, Long) -> Unit = { _, _ -> }, onSegment: suspend (String) -> Unit = {}): TranscriptionSession =
        createSessionWithOptions(null, onProcessed, onSegment)

    suspend fun createSession(options: RuntimeOptions, onProcessed: (Long, Long) -> Unit = { _, _ -> }, onSegment: suspend (String) -> Unit = {}): TranscriptionSession =
        createSessionWithOptions(options, onProcessed, onSegment)

    private suspend fun createSessionWithOptions(providedOptions: RuntimeOptions?, onProcessed: (Long, Long) -> Unit,
        onSegment: suspend (String) -> Unit): TranscriptionSession = withModelOperation { expected ->
        val owner = currentCoroutineContext()[Job]
        var created: TranscriptionSession? = null
        try {
            withContext(Dispatchers.IO) {
                sessionPreparation.prepare(providedOptions) { options ->
                    val app = UtterlaneApp.instance
                    // Preparation, segmentation, attribution and diagnostic activity all
                    // consume the same entry-point snapshot, including while queued.
                    check(initializeLocked(expected, options)) { _failure.value ?: context.getString(R.string.toast_model_load_failed) }
                    val rules = if (app.settingsRepository.dictionaryEnabled.first()) app.dictionaryManager.rules.value else emptyList()
                    val diarize = app.settingsRepository.diarizationEnabled.first()
                    val count = app.settingsRepository.speakerCount.first()
                    val speakerLabels = (1..8).map { context.getString(R.string.speaker_label, it) }
                    val unknownSpeaker = context.getString(R.string.speaker_unknown)
                    if (diarize && count != 1) check(app.diarizationModels.ensureVerified()) { context.getString(R.string.diarization_install_first) }
                    val sessionBackend = checkNotNull(recognizer)
                    val directory = File(context.cacheDir, "transcripts").apply { mkdirs() }
                    val store = TranscriptStore(File.createTempFile("transcript-", ".txt", directory))
                    val id = sessionIds.incrementAndGet()
                    synchronized(stateLock) {
                        if (expected != generation) { store.dispose(); error("Model was unloaded") }
                        sessions[id] = owner
                        refreshIdleTimerLocked()
                    }
                    TranscriptionSession(store, StreamingCorrections(rules), onSegment, decode = { window ->
                        withSessionBackend(expected, id, options) { backend ->
                            // Arbitrary ASR models need not supply word timings. Decode
                            // disjoint ownership for them rather than inventing timestamps.
                            val pcm = if (modelManager.selected.value.isCustom) window.samples.copyOfRange(
                                (window.ownedStart - window.startSample).toInt(), (window.ownedEnd - window.startSample).toInt()) else window.samples
                            val result = backend.transcribeWindow(pcm)
                            result.text ?: WindowText.select(result.tokens, result.timestamps, window)
                        }
                    }, onClosed = {
                        sessionBackend.endSession(id)
                        synchronized(stateLock) {
                            // A late close after reset belongs to the old generation.
                            if (sessions.containsKey(id)) {
                                sessions.remove(id)
                                activityOwnership.closeSession(id)
                                refreshIdleTimerLocked()
                            }
                        }
                    }, onProcessed = onProcessed,
                        decodeSpeakers = if (diarize) { window -> withSessionBackend(expected, id, options) { it.transcribeSpeakers(id, window, count, options) } } else null,
                        speakerLabel = { speaker -> speakerLabels.getOrElse(speaker) { unknownSpeaker } },
                        options = options,
                        // The session lease remains held until onClosed. Use the
                        // same snapshot/mutex/activity owner for its native EOF
                        // drain so even Immediate idle unloading cannot race it.
                        finishSpeakers = if (diarize) { { withSessionBackend(expected, id, options) { it.finishSpeakers(id, options) } } } else null
                    ).also { created = it }
                }
            }
        } catch (error: Throwable) {
            // withContext may discard an already-constructed result on cancellation.
            // Dispose that unreturned session rather than leaking its busy lease.
            withContext(NonCancellable + Dispatchers.IO) { created?.let { it.close(); it.store.dispose() } }
            throw error
        }
    }

    private suspend fun <T> withSessionBackend(expected: Long, id: Long, options: RuntimeOptions, action: (RecognitionBackend) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (backend, activityOperation) = synchronized(stateLock) {
                check(expected == generation && sessions.containsKey(id)) { "Model was unloaded" }
                checkNotNull(recognizer) to activityOwnership.begin(options, workerActivityOptions, id)
            }
            val result = try { runInterruptible(Dispatchers.IO) { action(backend) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                synchronized(stateLock) { if (expected == generation) { _failure.value = error.message; _isReady.value = false } }
                throw error
            }
            finally { synchronized(stateLock) { activityOwnership.finish(activityOperation) } }
            synchronized(stateLock) { check(expected == generation) { "Model was unloaded" } }
            result
        }
    }

    suspend fun selectModel(model: ModelDefinition) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = synchronized(stateLock) {
                check(sessions.isEmpty() && !MicrophoneSession.isBusy()) { context.getString(R.string.stream_busy) }
                check(!modelManager.isTransferring) { "A model transfer is running" }
                detachLocked()
            }
            old?.close()
            modelManager.select(model)
        }
    }
    suspend fun deleteDiarizationModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            synchronized(stateLock) { check(sessions.isEmpty() && !MicrophoneSession.isBusy()) { context.getString(R.string.stream_busy) } }
            UtterlaneApp.instance.diarizationModels.deleteModel()
        }
    }
    suspend fun deleteSelectedModel(expectedModelId: String = modelManager.selected.value.id) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Check inside the same lock as selection/deletion: a queued request
            // must never delete a different model than the user confirmed.
            check(modelManager.selected.value.id == expectedModelId) { context.getString(R.string.model_delete_selection_changed) }
            val old = synchronized(stateLock) {
                check(sessions.isEmpty() && !MicrophoneSession.isBusy()) { context.getString(R.string.stream_busy) }
                check(!modelManager.isTransferring) { "A model transfer is running" }
                detachLocked()
            }
            old?.close(); modelManager.deleteModel()
        }
    }

    /** Safe even if a worker is stuck in load/decode. Never waits for the inference mutex. */
    fun forceUnload() {
        val pending: List<Job?>
        val backend: RecognitionBackend?
        synchronized(stateLock) {
            generation++
            pending = sessions.values.toList()
            sessions.clear()
            backend = detachLocked()
        }
        MicrophoneSession.resetActive()
        pending.forEach { it?.cancel(CancellationException("Model forcibly unloaded")) }
        backend?.close()
        Log.i(TAG, "Recognition worker and active sessions reset")
    }
    suspend fun release() { forceUnload() }
    private fun detachLocked(clearFailure: Boolean = true): RecognitionBackend? {
        cancelIdleTimerLocked()
        idlePolicy.setIdle(false)
        val old = recognizer
        activityOwnership.reset()
        recognizer = null; loadedModelId = null; _isReady.value = false; _isLoading.value = false
        _activity.value = RecognitionActivity()
        if (clearFailure) _failure.value = null
        return old
    }
}

/**
 * The manager evaluates this only under its ownership lock. A saved ASR-thread
 * change requires fresh native construction at the next idle initialization;
 * active sessions keep their worker and budgets until every lease has drained.
 */
internal class RecognitionRuntimeConfiguration {
    enum class Change { KEEP, CONFIGURE, RELOAD }
    private var current: RuntimeOptions? = null

    fun change(requested: RuntimeOptions, hasSessions: Boolean): Change = when {
        current == null -> Change.RELOAD
        hasSessions -> Change.KEEP
        current?.asrThreads != requested.asrThreads -> Change.RELOAD
        current != requested -> Change.CONFIGURE
        else -> Change.KEEP
    }

    fun applied(options: RuntimeOptions) { current = options.requireValid() }
}
