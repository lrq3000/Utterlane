package io.github.lrq3000.utterlane.asr

import android.content.Context
import android.util.Log
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp
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
    private val backendFactory: (ModelDefinition, String) -> RecognitionBackend = { model, _ -> WorkerRecognitionBackend(context, model) }
) {
    companion object { private const val TAG = "RecognizerManager" }
    private val mutex = Mutex()
    private val stateLock = Any()
    private var recognizer: RecognitionBackend? = null
    private var loadedModelId: String? = null
    private var generation = 0L
    private val sessionIds = AtomicLong(0)
    private val sessions = mutableMapOf<Long, Job?>()
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure

    suspend fun initialize(): Boolean {
        val expected = synchronized(stateLock) { generation }
        return withContext(Dispatchers.IO) { mutex.withLock { initializeLocked(expected) } }
    }

    private suspend fun initializeLocked(expected: Long): Boolean {
        synchronized(stateLock) { if (expected != generation) return false }
        modelManager.initializeSelection()
        val model = modelManager.selected.value
        synchronized(stateLock) {
            if (expected != generation) return false
            if (_isReady.value && loadedModelId == model.id && recognizer?.isAvailable() == true) return true
            _isLoading.value = true
            _failure.value = null
        }
        var candidate: RecognitionBackend? = null
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            check(modelManager.ensureVerified()) { context.getString(R.string.model_error_checksum) }
            val previous = synchronized(stateLock) {
                if (expected != generation) return false
                check(sessions.isEmpty()) { "A transcription session is active" }
                val old = recognizer
                recognizer = null; loadedModelId = null; _isReady.value = false
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
                    }
                }
            }
            synchronized(stateLock) {
                if (expected != generation) { candidate.close(); return false }
                // Publish before preparation: reset can abort a blocked worker load.
                recognizer = candidate
            }
            candidate.prepare()
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (expected != generation) { candidate.close(); return false }
                check(candidate.isAvailable()) { "Recognition worker exited during initialization" }
                loadedModelId = model.id
                _isReady.value = true
            }
            Log.i(TAG, "Recognizer validated: ${model.id}, elapsed=${android.os.SystemClock.elapsedRealtime() - start}ms")
            true
        } catch (e: CancellationException) {
            candidate?.close()
            synchronized(stateLock) {
                if (expected == generation && candidate != null && recognizer === candidate) {
                    recognizer = null; loadedModelId = null; _isReady.value = false
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
                    }
                    _failure.value = "${model.name}: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            false
        } finally {
            synchronized(stateLock) { if (expected == generation) _isLoading.value = false }
        }
    }

    suspend fun ensureInitialized(): Boolean = initialize()
    fun isInitialized(): Boolean = _isReady.value

    suspend fun createSession(onProcessed: (Long, Long) -> Unit = { _, _ -> }, onSegment: suspend (String) -> Unit = {}): TranscriptionSession {
        val owner = currentCoroutineContext()[Job]
        val expected = synchronized(stateLock) { generation }
        var created: TranscriptionSession? = null
        return try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    check(initializeLocked(expected)) { _failure.value ?: context.getString(R.string.toast_model_load_failed) }
                    val app = UtterlaneApp.instance
                    val rules = if (app.settingsRepository.dictionaryEnabled.first()) app.dictionaryManager.rules.value else emptyList()
                    val directory = File(context.cacheDir, "transcripts").apply { mkdirs() }
                    val store = TranscriptStore(File.createTempFile("transcript-", ".txt", directory))
                    val id = sessionIds.incrementAndGet()
                    synchronized(stateLock) {
                        if (expected != generation) { store.dispose(); error("Model was unloaded") }
                        sessions[id] = owner
                    }
                    TranscriptionSession(store, StreamingCorrections(rules), onSegment, decode = { window ->
                        withContext(Dispatchers.IO) {
                            mutex.withLock {
                                val backend = synchronized(stateLock) {
                                    check(expected == generation && sessions.containsKey(id)) { "Model was unloaded" }
                                    checkNotNull(recognizer)
                                }
                                val result = try { backend.transcribeWindow(window.samples) }
                                catch (error: Exception) {
                                    synchronized(stateLock) { if (expected == generation) { _failure.value = error.message; _isReady.value = false } }
                                    throw error
                                }
                                synchronized(stateLock) { check(expected == generation) { "Model was unloaded" } }
                                WindowText.select(result.tokens, result.timestamps, window)
                            }
                        }
                    }, onClosed = { synchronized(stateLock) { sessions.remove(id) } }, onProcessed = onProcessed).also { created = it }
                }
            }
        } catch (error: Throwable) {
            // withContext may discard an already-constructed result on cancellation.
            // Dispose that unreturned session rather than leaking its busy lease.
            withContext(NonCancellable + Dispatchers.IO) { created?.let { it.close(); it.store.dispose() } }
            throw error
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
    suspend fun deleteSelectedModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
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
    private fun detachLocked(): RecognitionBackend? {
        val old = recognizer
        recognizer = null; loadedModelId = null; _isReady.value = false; _isLoading.value = false; _failure.value = null
        return old
    }
}
