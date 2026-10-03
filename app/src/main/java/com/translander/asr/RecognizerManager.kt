package com.translander.asr

import android.content.Context
import android.util.Log
import com.translander.TranslanderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File

/** One shared model; initialization, decoding and release have the same exclusion boundary. */
class RecognizerManager(private val context: Context, private val modelManager: ModelManager) {
    companion object { private const val TAG = "RecognizerManager" }
    @Volatile private var recognizer: RecognitionBackend? = null
    private var loadedModelId: String? = null
    private val activeSessions = java.util.concurrent.atomic.AtomicInteger(0)
    private val mutex = Mutex()
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { initializeLocked() }
    }

    private suspend fun initializeLocked(): Boolean {
            modelManager.initializeSelection()
            val model = modelManager.selected.value
            if (recognizer != null && loadedModelId == model.id) return true
            _isLoading.value = true
            return try {
                if (!modelManager.ensureVerified()) return false
                check(activeSessions.get() == 0) { "A transcription session is active" }
                recognizer?.close(); recognizer = null; loadedModelId = null; _isReady.value = false
                val candidate = if (model.backend == ModelBackend.SHERPA) {
                    ParakeetRecognizer(context, modelManager.getModelPath()).also { check(it.isReady()) { "ONNX model initialization failed" } }
                } else CrispParakeetBackend(File(modelManager.getModelPath(), "model.gguf").absolutePath)
                recognizer = candidate
                loadedModelId = model.id
                _isReady.value = true
                Log.i(TAG, "Recognizer initialized: ${model.id}")
                true
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize recognizer", e)
                false
            } finally { _isLoading.value = false }
    }

    suspend fun ensureInitialized(): Boolean = initialize()
    fun isInitialized(): Boolean = _isReady.value

    suspend fun createSession(onProcessed: (Long, Long) -> Unit = { _, _ -> }, onSegment: suspend (String) -> Unit = {}): TranscriptionSession {
        return withContext(Dispatchers.IO) {
          mutex.withLock {
            check(initializeLocked()) { context.getString(com.translander.R.string.toast_model_load_failed) }
            val app = TranslanderApp.instance
            val rules = if (app.settingsRepository.dictionaryEnabled.first()) app.dictionaryManager.rules.value else emptyList()
            val directory = File(context.cacheDir, "transcripts").apply { mkdirs() }
            val store = TranscriptStore(File.createTempFile("transcript-", ".txt", directory))
            activeSessions.incrementAndGet()
            TranscriptionSession(store, StreamingCorrections(rules), onSegment, decode = { window ->
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val result = checkNotNull(recognizer) { "Speech model was unloaded" }.transcribeWindow(window.samples)
                    WindowText.select(result.tokens, result.timestamps, window)
                }
            }
            }, onClosed = { activeSessions.decrementAndGet() }, onProcessed = onProcessed)
          }
        }
    }

    suspend fun selectModel(model: ModelDefinition) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(activeSessions.get() == 0 && !MicrophoneSession.isBusy()) { context.getString(com.translander.R.string.stream_busy) }
            check(!modelManager.isTransferring) { "A model transfer is running" }
            recognizer?.close(); recognizer = null; loadedModelId = null; _isReady.value = false
            modelManager.select(model)
        }
    }

    suspend fun deleteSelectedModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(activeSessions.get() == 0 && !MicrophoneSession.isBusy()) { context.getString(com.translander.R.string.stream_busy) }
            check(!modelManager.isTransferring) { "A model transfer is running" }
            recognizer?.close(); recognizer = null; loadedModelId = null; _isReady.value = false
            modelManager.deleteModel()
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(activeSessions.get() == 0 && !MicrophoneSession.isBusy()) { context.getString(com.translander.R.string.stream_busy) }
            recognizer?.close()
            recognizer = null
            loadedModelId = null
            _isReady.value = false
        }
    }
}
