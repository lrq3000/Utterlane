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
    @Volatile private var recognizer: ParakeetRecognizer? = null
    private val mutex = Mutex()
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (recognizer?.isReady() == true) return@withLock true
            if (!modelManager.isModelReady()) return@withLock false
            _isLoading.value = true
            try {
                val candidate = ParakeetRecognizer(context, modelManager.getModelPath())
                if (!candidate.isReady()) { candidate.release(); return@withLock false }
                recognizer = candidate
                _isReady.value = true
                Log.i(TAG, "Recognizer initialized: true")
                true
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize recognizer", e)
                false
            } finally { _isLoading.value = false }
        }
    }

    suspend fun ensureInitialized(): Boolean = initialize()
    fun isInitialized(): Boolean = _isReady.value

    suspend fun createSession(onSegment: suspend (String) -> Unit = {}): TranscriptionSession {
        check(ensureInitialized()) { context.getString(com.translander.R.string.toast_model_load_failed) }
        val app = TranslanderApp.instance
        val rules = if (app.settingsRepository.dictionaryEnabled.first()) app.dictionaryManager.rules.value else emptyList()
        val directory = File(context.cacheDir, "transcripts").apply { mkdirs() }
        val store = TranscriptStore(File.createTempFile("transcript-", ".txt", directory))
        return TranscriptionSession(store, StreamingCorrections(rules), onSegment) { window ->
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val result = checkNotNull(recognizer) { "Speech model was unloaded" }.transcribeWindow(window.samples)
                    WindowText.select(result.tokens, result.timestamps, window)
                }
            }
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        mutex.withLock {
            recognizer?.release()
            recognizer = null
            _isReady.value = false
        }
    }
}
