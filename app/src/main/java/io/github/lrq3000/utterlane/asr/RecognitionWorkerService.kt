package io.github.lrq3000.utterlane.asr

import android.app.Service
import android.content.Intent
import android.os.*
import android.util.Log
import io.github.lrq3000.utterlane.settings.RuntimeOptions
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
    const val RESULT = 6
    const val PROGRESS = 7
    const val PROCESS_SUFFIX = ":recognition"
    const val MAX_SAMPLES = 192000
}

/** Native ownership boundary: terminating this private process cannot corrupt the UI heap. */
class RecognitionWorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var backend: RecognitionBackend? = null
    private var textOnly = false
    // Read/written exclusively on the native executor, never polled via JNI on main.
    private var activeProgress: RecognitionProgressReporter? = null
    private class SpeakerSession(val processor: DiarizedWindowProcessor, val count: Int, val options: RuntimeOptions)
    private val speakerSessions = mutableMapOf<Long, SpeakerSession>()
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == RecognitionProtocol.END_SESSION) {
            val session = message.data.getLong("session")
            executor.execute { speakerSessions.remove(session)?.processor?.close() }
            return@Handler true
        }
        val reply = message.replyTo ?: return@Handler true
        val id = message.arg1
        val operation = message.what
        val data = message.data
        if (operation == RecognitionProtocol.HELLO) {
            respond(reply, id, Bundle().apply { putInt("pid", Process.myPid()) })
        } else {
            val progress = RecognitionProgressReporter(id) { packet ->
                respond(reply, id, Bundle().apply {
                    putLong("sequence", packet.sequence); putString("stage", packet.stage)
                    putLong("units", packet.completedUnits); putBoolean("completed", packet.completed)
                    putBoolean("opaque", packet.opaque)
                }, RecognitionProtocol.PROGRESS)
            }
            progress.stage("queued")
            executor.execute {
                activeProgress = progress
                progress.stage("executing")
                val result = try {
                    when (operation) {
                        RecognitionProtocol.LOAD -> load(data.getString("model"), OptionsCodec.fromBundle(requireNotNull(data.getBundle("options"))))
                        RecognitionProtocol.DECODE -> decode(data)
                        RecognitionProtocol.SPEAKERS -> speakers(data)
                        else -> error("Unknown recognition operation")
                    }
                } catch (failure: Throwable) {
                    // Linkage errors and native allocation failures must travel back
                    // to Settings too, not silently strand a loading coroutine.
                    Log.e("RecognitionWorker", "Recognition operation failed", failure)
                    Bundle().apply { putString("error", "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}") }
                } finally {
                    progress.close()
                    activeProgress = null
                }
                respond(reply, id, result)
            }
        }
        true
    }) }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun load(id: String?, options: RuntimeOptions): Bundle {
        val model = CustomModelManifest.load(filesDir, id) ?: ModelCatalog.find(id)
        require(model.id == id) { "Unknown recognition model" }
        val directory = File(filesDir, model.relativeDirectory)
        check(backend == null) { "Worker already owns a model" }
        val started = SystemClock.elapsedRealtime()
        activeProgress?.stage("model_load")
        val threads = RuntimeOptions.resolveThreads(options.asrThreads)
        val candidate = when (model.backend) {
            ModelBackend.CRISP -> if (model.isCustom) {
                CrispGenericBackend(File(directory, model.primaryFile).absolutePath, model.codecFile?.let { File(directory, it).absolutePath }, threads)
            } else CrispParakeetBackend(File(directory, "model.gguf").absolutePath, threads)
            ModelBackend.TRANSCRIBE_CPP -> TranscribeCppBackend(File(directory, "model.gguf").absolutePath, threads)
            ModelBackend.SHERPA -> ParakeetRecognizer(this, directory.absolutePath, threads).also { check(it.isReady()) { "ONNX initialization failed" } }
        }
        try {
            candidate.configure(options)
            activeProgress?.completeStage("model_load_complete")
            // mmap makes GGUF open very fast. Actually run both encoder and
            // decoder before announcing readiness; zero transcript is valid here.
            val result = transcribe(candidate, ShortArray(16000), "warmup")
            check(result.tokens.size == result.timestamps.size) { "Invalid native warm-up result" }
            // DWP retains this wrapper for the session, but it reads the current
            // request on every call, so its first request's reporter cannot leak.
            backend = object : RecognitionBackend by candidate {
                override fun transcribeWindow(samples: ShortArray) = transcribe(candidate, samples, "asr")
            }
            textOnly = model.isCustom
        } catch (failure: Throwable) { candidate.close(); throw failure }
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i("RecognitionWorker", "Validated ${model.id} in ${elapsed}ms, pid=${Process.myPid()}")
        return Bundle().apply { putLong("loadMs", elapsed) }
    }

    private fun transcribe(candidate: RecognitionBackend, samples: ShortArray, stage: String): WindowResult {
        val progress = checkNotNull(activeProgress)
        progress.stage(stage)
        candidate.setProgressListener(progress.callback(stage))
        return try {
            candidate.transcribeWindow(samples).also { progress.completeStage("${stage}_complete") }
        } finally { progress.endCallback(stage); candidate.setProgressListener { _, _ -> } }
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
        val options = OptionsCodec.fromBundle(requireNotNull(data.getBundle("options")))
        val session = speakerSessions[id] ?: run {
            check(speakerSessions.size < 4) { "Too many simultaneous speaker sessions" }
            val recognizer = checkNotNull(backend)
            val stream = speakerStream(count) {
                val path = File(filesDir, "${DiarizationModel.definition.relativeDirectory}/model.gguf")
                check(path.isFile && path.length() == DiarizationModel.definition.downloadBytes) { "Download the speaker diarization model in Settings first" }
                ObservedSpeakerStream(path.absolutePath, options)
            }
            SpeakerSession(DiarizedWindowProcessor(recognizer, stream, count, textOnly, options), count, options).also { speakerSessions[id] = it }
        }
        try {
            require(session.count == count && session.options == options) { "Speaker session options changed during recognition" }
            val spans = session.processor.process(AudioWindow(readPcm(data), data.getLong("start"), data.getLong("ownedStart"), data.getLong("ownedEnd"), data.getBoolean("final")))
            check(spans.size <= 8192 && spans.sumOf { it.text.length } <= 128000) { "Speaker result exceeds IPC budget" }
            return Bundle().apply {
                putStringArray("texts", spans.map { it.text }.toTypedArray())
                putIntArray("speakers", spans.map { it.speaker }.toIntArray())
            }
        } catch (failure: Throwable) {
            speakerSessions.remove(id)?.processor?.close(); throw failure
        } finally {
            if (data.getBoolean("final")) speakerSessions.remove(id)?.processor?.close()
        }
    }

    /** Native integration replaces this constructor with the options/progress overload. */
    @Suppress("UNUSED_PARAMETER")
    private fun getSpeakerStream(path: String, options: RuntimeOptions, progress: (Long, String) -> Unit): SpeakerProbabilityStream =
        CrispSpeakerStream(path)

    private inner class ObservedSpeakerStream(path: String, options: RuntimeOptions) : SpeakerProbabilityStream {
        @Volatile private var callback: (Long, String) -> Unit = checkNotNull(activeProgress).callback("speaker_load")
        private val stream: SpeakerProbabilityStream
        init {
            activeProgress?.stage("speaker_load")
            stream = getSpeakerStream(path, options) { count, stage -> callback(count, stage) }
            activeProgress?.completeStage("speaker_load_complete")
            activeProgress?.endCallback("speaker_load")
            callback = { _, _ -> }
        }
        override fun push(samples: ShortArray, final: Boolean): FloatArray {
            val progress = checkNotNull(activeProgress)
            progress.stage("speaker_inference")
            callback = progress.callback("speaker")
            return try { stream.push(samples, final).also { progress.completeStage("speaker_push_complete") } }
            finally { progress.endCallback("speaker"); callback = { _, _ -> } }
        }
        override fun close() { callback = { _, _ -> }; stream.close() }
    }

    companion object {
        /** Fixed-one labeling needs ASR only: never even construct native diarization. */
        internal fun speakerStream(count: Int, create: () -> SpeakerProbabilityStream): SpeakerProbabilityStream =
            if (count == 1) object : SpeakerProbabilityStream {
                override fun push(samples: ShortArray, final: Boolean) = floatArrayOf()
                override fun close() {}
            } else create()
    }

    private fun respond(recipient: Messenger, id: Int, data: Bundle, kind: Int = RecognitionProtocol.RESULT) {
        try { recipient.send(Message.obtain().apply { what = kind; arg1 = id; this.data = data }) }
        catch (_: RemoteException) { /* Client has unloaded/cancelled the model. */ }
    }
    override fun onDestroy() {
        // No unsafe free concurrent with JNI. Normally the client kills this
        // worker on unload; if Android unbinds it, serialize cleanup after work.
        executor.execute { speakerSessions.values.forEach { it.processor.close() }; speakerSessions.clear(); backend?.close(); backend = null }
        executor.shutdown()
        super.onDestroy()
    }
}
