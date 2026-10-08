package io.github.lrq3000.utterlane

import android.os.SystemClock
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.RecordingHistory

/** Local acoustic replay, including raw timing/posterior evidence, never bundled private audio. */
@RunWith(AndroidJUnit4::class)
class DiarizationFixtureAndroidTest {
    @Test fun segmentationPlans(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val pcm = readFixturePcm(app, "test-1-speaker-french")
        val plans = JSONArray()
        // Cheap screening uses the actual segmenter and decoder. Only profiles
        // that change boundaries need expensive neural recognition afterward.
        for (amplitude in listOf(200, 400, 800, 1600)) for (silenceMs in listOf(100, 200, 300, 600)) {
            val windows = JSONArray()
            var inputSamples = 0L
            val options = RuntimeOptions(silenceAmplitude = amplitude, silenceDurationMs = silenceMs)
            val segmenter = AudioSegmenter(options = options) { window ->
                inputSamples += window.samples.size
                windows.put(JSONObject().put("owned_end_ms", window.ownedEnd / 16.0)
                    .put("ready_audio_ms", (window.startSample + window.samples.size) / 16.0))
            }
            segmenter.accept(pcm); segmenter.finish()
            plans.put(JSONObject().put("amplitude", amplitude).put("silence_ms", silenceMs)
                .put("input_audio_ms", inputSamples / 16.0).put("windows", windows))
        }
        val output = File(app.getExternalFilesDir(null), "diarization-runs/segmentation-plans.json")
        output.parentFile!!.mkdirs()
        output.writeText(JSONObject().put("pcm_sha256", pcmSha256(pcm)).put("samples", pcm.size)
            .put("plans", plans).toString(2))
    }

    @Test fun workerReportsCompletedProgressDuringLongRequests(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        app.modelManager.initializeSelection()
        val oldModel = app.modelManager.selected.value
        val oldOptions = app.settingsRepository.runtimeOptions.first()
        val oldEnabled = app.settingsRepository.diarizationEnabled.first()
        val oldCount = app.settingsRepository.speakerCount.first()
        val maxAwake = java.util.concurrent.atomic.AtomicLong()
        val nativeProgress = java.util.concurrent.atomic.AtomicBoolean()
        val observer = launch(Dispatchers.Default) {
            app.recognizerManager.activity.collect { state ->
                maxAwake.updateAndGet { maxOf(it, state.elapsedMillis) }
                if (state.stage.startsWith("speaker/") && state.completedUnits > 0) nativeProgress.set(true)
            }
        }
        try {
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(ModelCatalog.DEFAULT)
            val weights = File(app.modelManager.directory().apply { mkdirs() }, "model.gguf")
            if (!weights.exists()) File("/sdcard/Download/parakeet-qa/parakeet-ultra-q8_0.gguf").copyTo(weights)
            val speakerWeights = File(app.diarizationModels.directory().apply { mkdirs() }, "model.gguf")
            if (!speakerWeights.exists()) File("/sdcard/Download/Nemotron-3-Diarization.q8_0.gguf").copyTo(speakerWeights)
            val options = RuntimeOptions(diarizationBatch = 1, unknownBridgeMs = 1000,
                inferenceStallSeconds = 45, absoluteOperationSeconds = 0, diagnostics = true)
            app.settingsRepository.setRuntimeOptions(options)
            app.settingsRepository.setDiarizationEnabled(true)
            app.settingsRepository.setSpeakerCount(0)
            TranscriptionPower(app).use {
                val session = app.recognizerManager.createSession()
                try {
                    AudioDecoder(app).decode("/sdcard/Download/diarization-qa/test-1-speaker-french.m4a", { session.accept(it) })
                    session.finish()
                    val text = session.store.readForTransfer()!!
                    assertTrue(text, text.contains("capture bien ou pas"))
                    assertTrue("No genuine native stage progress reached the client", nativeProgress.get())
                    val result = File(app.getExternalFilesDir(null), "diarization-runs/worker-progress.json")
                    result.parentFile!!.mkdirs()
                    result.writeText(JSONObject().put("stall_seconds", 45).put("maximum_request_awake_ms", maxAwake.get())
                        .put("native_progress", nativeProgress.get()).put("complete_transcript", true).toString(2))
                    android.util.Log.i("DiarizationFixture", "Worker survived: maximum awake=${maxAwake.get()}ms, stall budget=45000ms, genuine progress=${nativeProgress.get()}")
                } finally { session.close(); session.store.dispose() }
            }
        } finally {
            observer.cancel()
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(oldModel)
            app.settingsRepository.setRuntimeOptions(oldOptions)
            app.settingsRepository.setDiarizationEnabled(oldEnabled)
            app.settingsRepository.setSpeakerCount(oldCount)
        }
    }

    @Test fun replay(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val args = InstrumentationRegistry.getArguments()
        // The native override is read once per process. Run each configuration
        // through a separate instrumentation invocation, before loading models.
        val attention = args.getString("native_attention", "default")
        require(attention in listOf("default", "flash", "manual"))
        if (attention == "default") android.system.Os.unsetenv("CRISPASR_NEMOTRON3_DIAR_ATTN")
        else android.system.Os.setenv("CRISPASR_NEMOTRON3_DIAR_ATTN", attention, true)
        val fixture = args.getString("fixture", "test-1-speaker-french")
        require(fixture in listOf("test-1-speaker-french", "test-2-speakers-french-3-turns"))
        val tag = args.getString("tag", "candidate").also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
        val count = args.getString("speakers", "0").toInt().also { require(it in 0..8) }
        val enabled = args.getString("diarization", "true").toBooleanStrict()
        val repeats = args.getString("repeats", "1").toInt().also { require(it in 1..8) }
        val realtime = args.getString("realtime", "false").toBooleanStrict()
        val sourceTag = args.getString("asr_source_tag")?.also {
            require(it.matches(Regex("[a-zA-Z0-9_-]+")) && it != tag)
        }
        val recorded = sourceTag?.let {
            RecordedAsr(File(app.getExternalFilesDir(null), "diarization-runs/$it/$fixture.words.jsonl"))
        }
        val overrides = args.keySet().filter { it.startsWith("option_") }.associate {
            it.removePrefix("option_") to requireNotNull(args.getString(it))
        }
        val options = RuntimeOptions.parseDraft(RuntimeOptions().toMap() + overrides).let {
            requireNotNull(it.options) { it.errors.toString() }
        }
        val completedSamples = AtomicLong()
        val capture = if (realtime) {
            require(repeats == 1 && sourceTag == null) { "Real-time trials require one fresh <=30-second recording" }
            val pcm = readFixturePcm(app, fixture)
            RealtimeFixtureCapture(pcm, options.captureBlockMs * 16, completedSamples::get)
        } else null
        val directory = File(app.getExternalFilesDir(null), "diarization-runs/$tag").apply { mkdirs() }
        val performance = File(directory, "$fixture.performance.jsonl").apply { writeText("") }
        val words = File(directory, "$fixture.words.jsonl").apply { writeText("") }
        val frames = FileOutputStream(File(directory, "$fixture.probabilities.f32"))
        val stages = mutableMapOf<String, Long>()
        val nativeStageNanos = mutableMapOf<String, Long>()
        val forwardMillis = mutableListOf<Double>()
        var stageStarted = 0L
        var windowIndex = 0
        var owned = 0L
        var fed = 0L
        var nativeCalls = 0
        var firstTextAt = 0L
        var lastTextAt = 0L
        fun timing(stage: String, start: Long, samples: Long? = null) {
            performance.appendText(JSONObject().apply {
                put("recording", fixture); put("run_id", tag); put("stage", stage)
                put("phase", if (owned >= (options.nativeCacheFrames + options.nativeFifoFrames + options.nativeUpdateFrames) * 1280L) "warm" else "cold")
                put("chunk_id", windowIndex); put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                if (samples != null && samples > 0) put("audio_ms", samples / 16.0)
                put("audio_end_ms", owned / 16.0)
            }.toString() + "\n")
        }
        val load = SystemClock.elapsedRealtime()
        val modelPath = "/sdcard/Download/parakeet-qa/parakeet-ultra-q8_0.gguf"
        val native: RecognitionBackend = recorded ?: CrispParakeetBackend(modelPath, options.asrThreads)
        if (recorded == null) {
            timing("model_load", load)
            val warmup = SystemClock.elapsedRealtime()
            native.transcribeWindow(ShortArray(16000))
            timing("model_warmup", warmup)
        }
        val backend = object : RecognitionBackend by native {
            override fun transcribeWindow(samples: ShortArray): WindowResult {
                val start = SystemClock.elapsedRealtime()
                return native.transcribeWindow(samples).also { result ->
                    timing(if (recorded == null) "asr" else "asr_evidence", start, samples.size.toLong())
                    words.appendText(JSONObject().apply {
                        put("window", windowIndex); put("samples", samples.size)
                        put("pcm_sha256", pcmSha256(samples))
                        put("tokens", JSONArray(result.tokens.toList())); put("starts", JSONArray(result.timestamps.toList()))
                        put("ends", JSONArray(result.ends.toList()))
                    }.toString() + "\n")
                }
            }
        }
        val speaker = if (enabled && count != 1) {
            val start = SystemClock.elapsedRealtime()
            CrispSpeakerStream("/sdcard/Download/Nemotron-3-Diarization.q8_0.gguf", options) { units, stage ->
                val now = SystemClock.elapsedRealtimeNanos()
                val elapsed = now - stageStarted
                nativeStageNanos[stage] = (nativeStageNanos[stage] ?: 0L) + elapsed
                if (stage == "transformer") forwardMillis += elapsed / 1_000_000.0
                stageStarted = now
                assertTrue("Native progress must increase within an invocation", units > (stages[stage] ?: 0))
                stages[stage] = units
            }.also { timing("speaker_load", start) }
        } else null
        val observed = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean): FloatArray {
                stages.clear()
                val start = SystemClock.elapsedRealtime()
                // Callback intervals include graph preparation/compute in the
                // transformer stage and mel extraction in the features stage.
                // These are wall times, not sampled CPU or per-kernel timings.
                stageStarted = SystemClock.elapsedRealtimeNanos()
                val result = speaker?.push(samples, final) ?: floatArrayOf()
                nativeCalls += stages["transformer"]?.toInt() ?: 0
                val bytes = ByteBuffer.allocate(result.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(result); frames.write(bytes.array())
                fed += samples.size
                timing("speaker", start, samples.size.toLong())
                return result
            }
            override fun close() { speaker?.close() }
        }
        val processor = if (enabled) DiarizedWindowProcessor(backend, observed, count, options = options) else null
        val store = TranscriptStore(File(app.cacheDir, "fixture-${System.nanoTime()}.txt"))
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), { text ->
            if (text.isNotBlank()) {
                val now = SystemClock.elapsedRealtime()
                if (firstTextAt == 0L) firstTextAt = now
                lastTextAt = now
            }
        }, decode = { window ->
            words.appendText(JSONObject().put("window", windowIndex).put("start", window.startSample).put("owned_start", window.ownedStart).put("owned_end", window.ownedEnd).toString() + "\n")
            val result = backend.transcribeWindow(window.samples)
            WindowText.select(result.tokens, result.timestamps, window)
        }, onProcessed = { end, ms ->
            performance.appendText(JSONObject().apply {
                put("recording", fixture); put("run_id", tag); put("stage", "chunk"); put("chunk_id", windowIndex)
                put("phase", if (owned >= 960000) "warm" else "cold")
                put("elapsed_ms", ms); put("audio_ms", (end - owned) / 16.0); put("audio_end_ms", end / 16.0)
            }.toString() + "\n")
            owned = end; windowIndex++
            completedSamples.set(end)
        }, decodeSpeakers = processor?.let { p -> { window ->
            words.appendText(JSONObject().put("window", windowIndex).put("start", window.startSample).put("owned_start", window.ownedStart).put("owned_end", window.ownedEnd).toString() + "\n")
            p.process(window)
        } }, options = options, finishSpeakers = processor?.let { p -> { p.finish() } })
        val started = SystemClock.elapsedRealtime()
        var accepted = 0L
        try {
            if (capture != null) {
                val root = File(app.cacheDir, "realtime-$tag")
                val history = RecordingHistory(root)
                val recording = history.begin(HistoryRetention.NONE)
                var succeeded = false
                try {
                    // Reuse the real capture/writer/inference isolation and
                    // disk backlog. Models are already warm for this sweep.
                    val result = RecordingPipeline(capture, history, recording, options).run(
                        prepare = {}, accept = { session.accept(it) }, finish = { session.finish() })
                    assertNull(result.captureError); assertNull(result.storageError); assertNull(result.processingError)
                    accepted = capture.capturedSamples.toLong()
                    assertEquals(capture.sampleCount.toLong(), accepted)
                    assertEquals(accepted, recording.writtenSamples)
                    assertTrue("Capture exceeded 30 seconds", capture.stoppedAt - capture.startedAt <= 30000)
                    succeeded = true
                } finally {
                    recording.finish(!succeeded)
                    if (succeeded) root.delete()
                }
            } else {
                repeat(repeats) {
                    AudioDecoder(app).decode("/sdcard/Download/diarization-qa/$fixture.m4a", { pcm ->
                        // Replay the same fixed-size capture packets for On/Off; the
                        // native inference schedule is separate from packet transport.
                        var at = 0
                        while (at < pcm.size) {
                            val stop = minOf(at + 1600, pcm.size)
                            session.accept(pcm.copyOfRange(at, stop)); accepted += stop - at; at = stop
                        }
                    })
                }
                session.finish()
            }
            val finishedAt = SystemClock.elapsedRealtime()
            val timingOrigin = capture?.startedAt ?: started
            recorded?.requireConsumed()
            File(directory, "${fixture}_transcript_$tag.txt").writeText(store.file.readText())
            File(directory, "$fixture.summary.json").writeText(JSONObject().apply {
                put("options", JSONObject(options.toMap())); put("diarization", enabled); put("speakers", count)
                put("samples", accepted); put("processed_samples", owned); put("fed_to_speakers", fed)
                put("native_forwards", nativeCalls); put("elapsed_ms", finishedAt - timingOrigin)
                // Real-time mode starts its clock at actual capture dispatch;
                // file mode measures publication during unpaced processing.
                put("realtime", realtime)
                put("capture", capture?.report(finishedAt) ?: JSONObject.NULL)
                put("first_text_ms", if (firstTextAt == 0L) JSONObject.NULL else firstTextAt - timingOrigin)
                put("first_text_with_setup_ms", if (firstTextAt == 0L) JSONObject.NULL else firstTextAt - load)
                put("last_text_ms", if (lastTextAt == 0L) JSONObject.NULL else lastTextAt - timingOrigin)
                put("model_setup_ms", timingOrigin - load)
                put("native_attention", attention)
                put("asr_source_tag", sourceTag ?: JSONObject.NULL)
                put("native_stage_ms", JSONObject(nativeStageNanos.mapValues { it.value / 1_000_000.0 }))
                put("native_forward_ms", JSONArray(forwardMillis))
            }.toString(2))
            assertEquals(accepted, owned)
            if (enabled && count != 1) { assertEquals(accepted, fed); assertTrue(nativeCalls > 0) }
            android.util.Log.i("DiarizationFixture", "$fixture $tag: samples=$accepted forwards=$nativeCalls elapsed=${SystemClock.elapsedRealtime() - started}ms")
        } finally { session.close(); processor?.close(); native.close(); frames.close(); store.dispose() }
    }

    /** Recompute native speakers while holding real ASR words and windows fixed.
     * This is a diarization microbenchmark, never an end-to-end speed result.
     * PCM hashes make a changed fixture, cut, or replay length fail visibly.
     */
    private class RecordedAsr(file: File) : RecognitionBackend {
        private val windows: Iterator<JSONObject>
        init {
            require(file.length() in 1..10_000_000) { "Missing or oversized ASR evidence" }
            windows = file.useLines { lines -> lines.filter { it.isNotBlank() }
                .map { JSONObject(it) }.filter { it.has("tokens") }.toList() }.iterator()
        }
        override fun transcribeWindow(samples: ShortArray): WindowResult {
            check(windows.hasNext()) { "ASR evidence exhausted before the audio" }
            val window = windows.next()
            check(window.getInt("samples") == samples.size && window.getString("pcm_sha256") == pcmSha256(samples)) {
                "ASR evidence PCM differs from this window"
            }
            val tokens = window.getJSONArray("tokens")
            val starts = window.getJSONArray("starts")
            val ends = window.getJSONArray("ends")
            return WindowResult(Array(tokens.length()) { tokens.getString(it) },
                FloatArray(starts.length()) { starts.getDouble(it).toFloat() },
                ends = FloatArray(ends.length()) { ends.getDouble(it).toFloat() })
        }
        fun requireConsumed() { check(!windows.hasNext()) { "ASR evidence outlasted the audio" } }
        override fun close() {}
    }

    companion object {
        /** Decode before timing capture; keep all PCM but reject >30-second inputs. */
        private suspend fun readFixturePcm(context: Context, fixture: String): ShortArray {
            val pieces = mutableListOf<ShortArray>()
            var length = 0
            AudioDecoder(context).decode("/sdcard/Download/diarization-qa/$fixture.m4a", { pcm ->
                require(length + pcm.size <= 30 * 16000) { "Real-time fixture exceeds 30 seconds" }
                pieces += pcm; length += pcm.size
            })
            val pcm = ShortArray(length)
            var offset = 0
            for (piece in pieces) { piece.copyInto(pcm, offset); offset += piece.size }
            return pcm
        }

        private fun pcmSha256(samples: ShortArray): String {
            val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.asShortBuffer().put(samples)
            return java.security.MessageDigest.getInstance("SHA-256").digest(pcm.array()).joinToString("") { "%02x".format(it) }
        }
    }
}
