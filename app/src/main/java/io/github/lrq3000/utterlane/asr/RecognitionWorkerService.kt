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
    const val SPEAKERS = 4
    const val END_SESSION = 5
    const val PROCESS_SUFFIX = ":recognition"
    const val MAX_SAMPLES = 192000
}

/** Native ownership boundary: terminating this private process cannot corrupt the UI heap. */
class RecognitionWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var backend: RecognitionBackend? = null
    private var textOnly = false
    private val speakerSessions = mutableMapOf<Long, DiarizedWindowProcessor>()
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == RecognitionProtocol.END_SESSION) {
            val session = message.data.getLong("session")
            executor.execute { speakerSessions.remove(session)?.close() }
            return@Handler true
        }
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
                    RecognitionProtocol.SPEAKERS -> speakers(data)
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
        val model = CustomModelManifest.load(filesDir, id) ?: ModelCatalog.find(id)
        require(model.id == id) { "Unknown recognition model" }
        val directory = File(filesDir, model.relativeDirectory)
        check(backend == null) { "Worker already owns a model" }
        val started = SystemClock.elapsedRealtime()
        val candidate = if (model.isCustom) {
            CrispGenericBackend(File(directory, model.primaryFile).absolutePath, model.codecFile?.let { File(directory, it).absolutePath })
        } else if (model.backend == ModelBackend.CRISP) {
            CrispParakeetBackend(File(directory, "model.gguf").absolutePath)
        } else ParakeetRecognizer(this, directory.absolutePath).also { check(it.isReady()) { "ONNX initialization failed" } }
        try {
            // mmap makes GGUF open very fast. Actually run both encoder and
            // decoder before announcing readiness; zero transcript is valid here.
            val result = candidate.transcribeWindow(ShortArray(16000))
            check(result.tokens.size == result.timestamps.size) { "Invalid native warm-up result" }
            backend = candidate
            textOnly = model.isCustom
        } catch (failure: Throwable) { candidate.close(); throw failure }
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i("RecognitionWorker", "Validated ${model.id} in ${elapsed}ms, pid=${Process.myPid()}")
        return Bundle().apply { putLong("loadMs", elapsed) }
    }

    private fun decode(data: Bundle): Bundle {
        val result = checkNotNull(backend) { "Model is not loaded" }.transcribeWindow(readPcm(data))
        check(result.tokens.size == result.timestamps.size && result.tokens.size <= 8192) { "Invalid native result size" }
        return Bundle().apply { putStringArray("tokens", result.tokens); putFloatArray("timestamps", result.timestamps); putString("text", result.text) }
    }
    private fun readPcm(data: Bundle): ShortArray {
        val bytes = requireNotNull(data.getByteArray("pcm"))
        require(bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= RecognitionProtocol.MAX_SAMPLES * 2)
        val pcm = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        return pcm
    }
    private fun speakers(data: Bundle): Bundle {
        val id = data.getLong("session")
        val count = data.getInt("count")
        require(id > 0 && count in 0..8)
        val processor = speakerSessions[id] ?: run {
            check(speakerSessions.size < 4) { "Too many simultaneous speaker sessions" }
            val path = File(filesDir, "${DiarizationModel.definition.relativeDirectory}/model.gguf")
            check(path.isFile && path.length() == DiarizationModel.definition.downloadBytes) { "Download the speaker diarization model in Settings first" }
            DiarizedWindowProcessor(checkNotNull(backend), CrispSpeakerStream(path.absolutePath), count, textOnly).also { speakerSessions[id] = it }
        }
        try {
            val spans = processor.process(AudioWindow(readPcm(data), data.getLong("start"), data.getLong("ownedStart"), data.getLong("ownedEnd"), data.getBoolean("final")))
            check(spans.size <= 8192 && spans.sumOf { it.text.length } <= 128000) { "Speaker result exceeds IPC budget" }
            return Bundle().apply {
                putStringArray("texts", spans.map { it.text }.toTypedArray())
                putIntArray("speakers", spans.map { it.speaker }.toIntArray())
            }
        } catch (failure: Throwable) {
            speakerSessions.remove(id)?.close(); throw failure
        } finally {
            if (data.getBoolean("final")) speakerSessions.remove(id)?.close()
        }
    }
    private fun respond(recipient: Messenger, id: Int, data: Bundle) {
        try { recipient.send(Message.obtain().apply { arg1 = id; this.data = data }) }
        catch (_: RemoteException) { /* Client has unloaded/cancelled the model. */ }
    }
    override fun onDestroy() {
        // No unsafe free concurrent with JNI. Normally the client kills this
        // worker on unload; if Android unbinds it, serialize cleanup after work.
        executor.execute { speakerSessions.values.forEach { it.close() }; speakerSessions.clear(); backend?.close(); backend = null }
        executor.shutdown()
        super.onDestroy()
    }
}
