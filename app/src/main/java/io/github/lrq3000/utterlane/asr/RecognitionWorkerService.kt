package io.github.lrq3000.utterlane.asr

import android.app.Service
import android.content.Intent
import android.os.*
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

internal object RecognitionProtocol {
    const val HELLO = 1
    const val LOAD = 2
    const val DECODE = 3
    const val PROCESS_SUFFIX = ":recognition"
    const val MAX_SAMPLES = 192000
}

/** Native ownership boundary: terminating this private process cannot corrupt the UI heap. */
class RecognitionWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var backend: RecognitionBackend? = null
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper()) { message ->
        val reply = message.replyTo ?: return@Handler true
        val id = message.arg1
        val operation = message.what
        val data = message.data
        if (operation == RecognitionProtocol.HELLO) {
            respond(reply, id, Bundle().apply { putInt("pid", Process.myPid()) })
        } else executor.execute {
            val result = try {
                when (operation) {
                    RecognitionProtocol.LOAD -> load(data.getString("model"))
                    RecognitionProtocol.DECODE -> decode(data)
                    else -> error("Unknown recognition operation")
                }
            } catch (failure: Throwable) {
                // Linkage errors and native allocation failures must travel back
                // to Settings too, not silently strand a loading coroutine.
                Log.e("RecognitionWorker", "Recognition operation failed", failure)
                Bundle().apply { putString("error", "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}") }
            }
            respond(reply, id, result)
        }
        true
    }) }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun load(id: String?): Bundle {
        val model = ModelCatalog.find(id)
        require(model.id == id) { "Unknown recognition model" }
        val directory = File(filesDir, model.relativeDirectory)
        check(backend == null) { "Worker already owns a model" }
        val started = SystemClock.elapsedRealtime()
        val candidate = when (model.backend) {
            ModelBackend.CRISP -> CrispParakeetBackend(File(directory, "model.gguf").absolutePath)
            ModelBackend.TRANSCRIBE_CPP -> TranscribeCppBackend(File(directory, "model.gguf").absolutePath)
            ModelBackend.SHERPA -> ParakeetRecognizer(this, directory.absolutePath).also { check(it.isReady()) { "ONNX initialization failed" } }
        }
        try {
            // mmap makes GGUF open very fast. Actually run both encoder and
            // decoder before announcing readiness; zero transcript is valid here.
            val result = candidate.transcribeWindow(ShortArray(16000))
            check(result.tokens.size == result.timestamps.size) { "Invalid native warm-up result" }
            backend = candidate
        } catch (failure: Throwable) { candidate.close(); throw failure }
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i("RecognitionWorker", "Validated ${model.id} in ${elapsed}ms, pid=${Process.myPid()}")
        return Bundle().apply { putLong("loadMs", elapsed) }
    }

    private fun decode(data: Bundle): Bundle {
        val bytes = requireNotNull(data.getByteArray("pcm"))
        require(bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= RecognitionProtocol.MAX_SAMPLES * 2)
        val pcm = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val result = checkNotNull(backend) { "Model is not loaded" }.transcribeWindow(pcm)
        check(result.tokens.size == result.timestamps.size && result.tokens.size <= 8192) { "Invalid native result size" }
        return Bundle().apply { putStringArray("tokens", result.tokens); putFloatArray("timestamps", result.timestamps) }
    }
    private fun respond(recipient: Messenger, id: Int, data: Bundle) {
        try { recipient.send(Message.obtain().apply { arg1 = id; this.data = data }) }
        catch (_: RemoteException) { /* Client has unloaded/cancelled the model. */ }
    }
    override fun onDestroy() {
        // No unsafe free concurrent with JNI. Normally the client kills this
        // worker on unload; if Android unbinds it, serialize cleanup after work.
        executor.execute { backend?.close(); backend = null }
        executor.shutdown()
        super.onDestroy()
    }
}
