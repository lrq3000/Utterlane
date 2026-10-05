package io.github.lrq3000.utterlane

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Local acoustic replay, including raw timing/posterior evidence, never bundled private audio. */
@RunWith(AndroidJUnit4::class)
class DiarizationFixtureAndroidTest {
    @Test fun replay(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val args = InstrumentationRegistry.getArguments()
        val fixture = args.getString("fixture", "test-1-speaker-french")
        require(fixture in listOf("test-1-speaker-french", "test-2-speakers-french-3-turns"))
        val tag = args.getString("tag", "candidate").also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
        val count = args.getString("speakers", "0").toInt().also { require(it in 0..8) }
        val enabled = args.getString("diarization", "true").toBooleanStrict()
        val repeats = args.getString("repeats", "1").toInt().also { require(it in 1..8) }
        val overrides = args.keySet().filter { it.startsWith("option_") }.associate {
            it.removePrefix("option_") to requireNotNull(args.getString(it))
        }
        val options = RuntimeOptions.parseDraft(RuntimeOptions().toMap() + overrides).let {
            requireNotNull(it.options) { it.errors.toString() }
        }
        val directory = File(app.getExternalFilesDir(null), "diarization-runs/$tag").apply { mkdirs() }
        val performance = File(directory, "$fixture.performance.jsonl").apply { writeText("") }
        val words = File(directory, "$fixture.words.jsonl").apply { writeText("") }
        val frames = FileOutputStream(File(directory, "$fixture.probabilities.f32"))
        val stages = mutableMapOf<String, Long>()
        var windowIndex = 0
        var owned = 0L
        var fed = 0L
        var nativeCalls = 0
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
        val native = CrispParakeetBackend(modelPath, options.asrThreads)
        timing("model_load", load)
        val warmup = SystemClock.elapsedRealtime()
        native.transcribeWindow(ShortArray(16000))
        timing("model_warmup", warmup)
        val backend = object : RecognitionBackend by native {
            override fun transcribeWindow(samples: ShortArray): WindowResult {
                val start = SystemClock.elapsedRealtime()
                return native.transcribeWindow(samples).also { result ->
                    timing("asr", start, samples.size.toLong())
                    words.appendText(JSONObject().apply {
                        put("window", windowIndex); put("samples", samples.size)
                        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        pcm.asShortBuffer().put(samples)
                        put("pcm_sha256", java.security.MessageDigest.getInstance("SHA-256").digest(pcm.array()).joinToString("") { "%02x".format(it) })
                        put("tokens", JSONArray(result.tokens.toList())); put("starts", JSONArray(result.timestamps.toList()))
                        put("ends", JSONArray(result.ends.toList()))
                    }.toString() + "\n")
                }
            }
        }
        val speaker = if (enabled && count != 1) {
            val start = SystemClock.elapsedRealtime()
            CrispSpeakerStream("/sdcard/Download/Nemotron-3-Diarization.q8_0.gguf", options) { units, stage ->
                assertTrue("Native progress must increase within an invocation", units > (stages[stage] ?: 0))
                stages[stage] = units
            }.also { timing("speaker_load", start) }
        } else null
        val observed = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean): FloatArray {
                stages.clear()
                val start = SystemClock.elapsedRealtime()
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
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), {}, decode = { window ->
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
        }, decodeSpeakers = processor?.let { p -> { window ->
            words.appendText(JSONObject().put("window", windowIndex).put("start", window.startSample).put("owned_start", window.ownedStart).put("owned_end", window.ownedEnd).toString() + "\n")
            p.process(window)
        } }, options = options)
        val started = SystemClock.elapsedRealtime()
        var accepted = 0L
        try {
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
            File(directory, "${fixture}_transcript_$tag.txt").writeText(store.file.readText())
            File(directory, "$fixture.summary.json").writeText(JSONObject().apply {
                put("options", JSONObject(options.toMap())); put("diarization", enabled); put("speakers", count)
                put("samples", accepted); put("processed_samples", owned); put("fed_to_speakers", fed)
                put("native_forwards", nativeCalls); put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            }.toString(2))
            assertEquals(accepted, owned)
            if (enabled && count != 1) { assertEquals(accepted, fed); assertTrue(nativeCalls > 0) }
            android.util.Log.i("DiarizationFixture", "$fixture $tag: samples=$accepted forwards=$nativeCalls elapsed=${SystemClock.elapsedRealtime() - started}ms")
        } finally { session.close(); processor?.close(); native.close(); frames.close(); store.dispose() }
    }
}
