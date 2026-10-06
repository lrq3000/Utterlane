package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reuses private acoustic evidence; the replay backend never loads neural models. */
@RunWith(AndroidJUnit4::class)
class DiarizationEvidenceAndroidTest {
    @Test fun replay() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val sourceTag = checkedTag(args.getString("source_tag", "warmed"))
        val outputTag = checkedTag(args.getString("output_tag", "relabel-warmed"))
        require(sourceTag != outputTag) { "Evidence and output tags must differ" }
        val fixture = args.getString("fixture", "test-2-speakers-french-3-turns")
        require(fixture in setOf("test-1-speaker-french", "test-2-speakers-french-3-turns"))
        val root = File(requireNotNull(app.getExternalFilesDir(null)), "diarization-runs")
        val source = File(root, sourceTag)
        val summary = JSONObject(readBounded(File(source, "$fixture.summary.json"), 65536))
        val sourceMap = summary.getJSONObject("options").let { json ->
            json.keys().asSequence().associateWith { json.getString(it) }
        }
        // Old captures lack newer attribution keys, but acoustic settings must never be guessed.
        require(sourceMap.keys.containsAll(RuntimeOptions().toMap().keys - labelKeys))
        require(sourceMap.keys.all { it in RuntimeOptions().toMap() }) { "Unsupported source option" }
        val sourceOptions = parseOptions(sourceMap)
        val overrides = args.keySet().filter { it.startsWith("option_") }.associate {
            it.removePrefix("option_") to requireNotNull(args.getString(it))
        }
        val targetOptions = targetOptions(sourceOptions, overrides)
        val total = summary.integer("samples").also { require(it in 1..MAX_SAMPLES) }
        require(summary.getBoolean("diarization")) { "Capture has no speaker evidence" }
        val count = summary.integer("speakers").also { require(it == 0L || it in 2..8) }.toInt()
        assertEquals(total, summary.integer("processed_samples"))
        assertEquals(total, summary.integer("fed_to_speakers"))

        val windows = mutableListOf<Pair<JSONObject, JSONObject>>()
        val observed = mutableMapOf<Int, JSONObject>()
        var boundary: JSONObject? = null
        var tokens = 0
        for (line in readBounded(File(source, "$fixture.words.jsonl"), 8 * 1024 * 1024).lineSequence()) {
            if (line.isBlank()) continue
            val json = JSONObject(line)
            if (json.has("native_window")) {
                val index = json.integer("native_window").also { require(it in 0..1999) }.toInt()
                require(observed.put(index, json) == null) { "Duplicate native call $index" }
            } else if (json.has("tokens")) {
                require(windows.size < 2000)
                tokens += json.getJSONArray("tokens").length()
                require(tokens <= 20000) { "Fixture exceeds 20k captured tokens" }
                windows += requireNotNull(boundary) to json
                boundary = null
            } else {
                require(boundary == null) { "Missing ASR result" }
                boundary = json
            }
        }
        require(boundary == null && windows.isNotEmpty())
        // A partially captured schedule is ambiguous: never mix observed and guessed availability.
        require(observed.isEmpty() || observed.keys == windows.indices.toSet())
        val probabilities = File(source, "$fixture.probabilities.f32")
        require(probabilities.isFile && probabilities.length() % 32 == 0L &&
            probabilities.length() <= total / 160 * 32) { "Invalid 8-channel probability dump" }
        val totalRows = (probabilities.length() / 32).toInt()
        val schedule = NativeFrameSchedule(sourceOptions)
        var index = 0
        var fed = 0L
        var rows = 0
        var asrCalls = 0
        var speakerCalls = 0
        var sampleCount = 0
        lateinit var result: WindowResult
        val backend = object : RecognitionBackend {
            override fun transcribeWindow(samples: ShortArray): WindowResult {
                assertEquals(sampleCount, samples.size)
                assertEquals(index, asrCalls++)
                return result
            }
            override fun close() {}
        }
        val store = TranscriptStore(File.createTempFile("evidence-", ".txt", app.cacheDir))
        val formatter = SpeakerText(StreamingCorrections(emptyList())) {
            if (it < 0) "Unknown speaker" else "Speaker ${it + 1}"
        }
        try {
            DataInputStream(probabilities.inputStream().buffered()).use { input ->
                val stream = object : SpeakerProbabilityStream {
                    override fun push(samples: ShortArray, final: Boolean): FloatArray {
                        assertEquals(index, speakerCalls++)
                        assertEquals(index == windows.lastIndex, final)
                        fed += samples.size
                        val native = observed[index]
                        if (native?.has("samples") == true) assertEquals(samples.size.toLong(), native.integer("samples"))
                        if (native?.has("final") == true) assertEquals(final, native.getBoolean("final"))
                        val delta = native?.integer("returned_frames")?.also {
                            require(it in 0..totalRows.toLong())
                        }?.toInt() ?: schedule.advance(fed, final)
                        require(rows + delta <= totalRows && rows + delta <= fed / 160) {
                            "Invalid probability availability at window $index"
                        }
                        rows += delta
                        // Only this call's available prefix enters the timeline; no future rows leak in.
                        val bytes = ByteArray(delta * 32).also { input.readFully(it) }
                        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        return FloatArray(delta * 8) {
                            buffer.float.also { require(it.isFinite() && it in 0f..1f) }
                        }
                    }
                    override fun close() {}
                }
                DiarizedWindowProcessor(backend, stream, count, options = targetOptions).use { processor ->
                    var owned = 0L
                    var previousStart = 0L
                    for ((position, pair) in windows.withIndex()) {
                        index = position
                        val (meta, raw) = pair
                        assertEquals(index.toLong(), meta.integer("window"))
                        assertEquals(index.toLong(), raw.integer("window"))
                        sampleCount = raw.integer("samples").also { require(it in 1..192000) }.toInt()
                        val start = meta.integer("start")
                        val ownedStart = meta.integer("owned_start")
                        val ownedEnd = meta.integer("owned_end")
                        val end = start + sampleCount
                        require(start in previousStart..fed && end in fed..total &&
                            ownedStart == owned && ownedStart in start..end && ownedEnd in (ownedStart + 1)..end)
                        result = recordedResult(raw, sampleCount)
                        // Do not re-segment zero PCM: original ASR overlap/ownership is acoustic evidence too.
                        val window = AudioWindow(ShortArray(sampleCount), start, ownedStart, ownedEnd, index == windows.lastIndex)
                        for (text in formatter.accept(processor.process(window))) store.append(text)
                        assertEquals(end, fed)
                        owned = ownedEnd
                        previousStart = start
                    }
                    store.append(formatter.finish())
                    assertEquals(total, owned)
                }
                assertEquals("Unconsumed probability rows", totalRows, rows)
                assertEquals(-1, input.read())
            }
            assertEquals(total, fed)
            assertEquals(windows.size, asrCalls)
            assertEquals(windows.size, speakerCalls)
            val output = File(root, outputTag).apply { check(mkdirs() || isDirectory) }
            store.file.copyTo(File(output, "${fixture}_transcript_$outputTag.txt"), overwrite = true)
            File(output, "$fixture.summary.json").writeText(JSONObject().apply {
                put("evidence_replay", true); put("nativeInference", false)
                put("source_tag", sourceTag); put("output_tag", outputTag)
                put("source_options", JSONObject(sourceMap)); put("target_options", JSONObject(targetOptions.toMap()))
                put("availability", if (observed.isEmpty()) "reconstructed" else "recorded")
                put("samples", total); put("fed_to_speakers", fed); put("processed_samples", total)
                put("source_rows", totalRows); put("total_rows", rows)
                put("source_window_count", windows.size); put("window_count", asrCalls)
            }.toString(2))
        } finally { store.dispose() }
    }

    @Test fun scheduleRespectsFirstAndLaterBoundaries() {
        for ((mode, c, r) in listOf(Triple("very_low_latency", 6, 2), Triple("low_latency", 9, 4),
            Triple("ultra_low_latency", 3, 1))) for (batch in listOf(1, 4, 8)) {
            val schedule = NativeFrameSchedule(RuntimeOptions(diarizationMode = mode, diarizationBatch = batch))
            val first = (c + r) * 1280L + 40
            assertEquals(0, schedule.advance(first - 1, false))
            assertEquals(c * 8, schedule.advance(first, false))
            val later = c * 1280L + (c + r) * 1280L + 144
            assertEquals(0, schedule.advance(later - 1, false))
            assertEquals(c * 8, schedule.advance(later, false))
        }
    }

    @Test fun scheduleDrainsAllBatchesAndHandlesCenteredFftTail() {
        for (batch in listOf(1, 4, 8)) {
            val schedule = NativeFrameSchedule(RuntimeOptions(diarizationBatch = batch))
            assertEquals(432, schedule.advance(77790, false)) // First warmed ASR window: >8 base chunks.
            assertEquals(720, schedule.advance(192000, false))
            assertEquals(0, schedule.advance(192000, false))
            assertEquals(47, schedule.advance(192000, true)) // EOF is 1199 rows, not 1200.
        }
        for ((samples, rows) in listOf(0L to 0, 159L to 0, 160L to 1, 10279L to 64, 10280L to 63)) {
            assertEquals(rows, NativeFrameSchedule(RuntimeOptions()).advance(samples, true))
        }
        assertEquals(9366, NativeFrameSchedule(RuntimeOptions()).advance(1498710, true))
    }

    @Test fun overridesOnlyChangeAttribution() {
        val source = RuntimeOptions(diarizationMode = "low_latency", diarizationBatch = 1, unknownBridgeMs = 0)
        val target = targetOptions(source, mapOf("speaker_threshold" to "0.6"))
        assertEquals(source.toMap().filterKeys { it !in labelKeys }, target.toMap().filterKeys { it !in labelKeys })
        assertEquals(RuntimeOptions().unknownBridgeMs, target.unknownBridgeMs)
        assertEquals(0.6f, target.speakerThreshold)
        for (key in source.toMap().keys - labelKeys) {
            assertThrows(IllegalArgumentException::class.java) { targetOptions(source, mapOf(key to source.toMap().getValue(key))) }
        }
        assertThrows(IllegalArgumentException::class.java) { targetOptions(source, mapOf("speaker_threshold" to "NaN")) }
        assertThrows(IllegalArgumentException::class.java) { targetOptions(source, mapOf("typo" to "1")) }
    }

    @Test fun scheduleRejectsBackwardOversizedAndPostFinalInput() {
        val schedule = NativeFrameSchedule(RuntimeOptions())
        schedule.advance(16000, false)
        assertThrows(IllegalArgumentException::class.java) { schedule.advance(15999, false) }
        assertThrows(IllegalArgumentException::class.java) { schedule.advance(MAX_SAMPLES + 1, false) }
        schedule.advance(16000, true)
        assertThrows(IllegalArgumentException::class.java) { schedule.advance(16000, true) }
    }

    @Test fun rejectsMalformedWordEvidenceWithoutRepairingExactEnds() {
        fun record() = JSONObject().put("tokens", org.json.JSONArray(listOf(" One", " two")))
            .put("starts", org.json.JSONArray(listOf(0.1, 0.3)))
            .put("ends", org.json.JSONArray(listOf(0.2, 0.4))).put("pcm_sha256", "0".repeat(64))
        assertArrayEquals(floatArrayOf(0.2f, 0.4f), recordedResult(record(), 16000).ends, 0f)
        for (ends in listOf(listOf(0.2), listOf(0.2, 0.25), listOf(0.5, 0.4), listOf(0.2, 1.1))) {
            assertThrows(IllegalArgumentException::class.java) {
                recordedResult(record().put("ends", org.json.JSONArray(ends)), 16000)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            recordedResult(record().put("starts", org.json.JSONArray(listOf("NaN", 0.3))), 16000)
        }
    }

    private fun recordedResult(raw: JSONObject, samples: Int): WindowResult {
        val tokens = raw.getJSONArray("tokens")
        val starts = raw.getJSONArray("starts")
        val ends = raw.getJSONArray("ends")
        require(tokens.length() <= 8192 && starts.length() == tokens.length() && ends.length() == tokens.length())
        require(raw.getString("pcm_sha256").matches(Regex("[a-fA-F0-9]{64}")))
        val text = Array(tokens.length()) { tokens.getString(it).also { require(it.length <= 4096) } }
        val from = FloatArray(text.size) { starts.getDouble(it).toFloat() }
        val to = FloatArray(text.size) { ends.getDouble(it).toFloat() }
        for (i in text.indices) require(from[i].isFinite() && to[i].isFinite() && from[i] >= 0 &&
            to[i] >= from[i] && to[i] <= (samples + 1) / 16000.0 &&
            (i == 0 || (from[i] >= from[i - 1] && to[i] >= to[i - 1]))) { "Invalid word timing at token $i" }
        return WindowResult(text, from, ends = to) // Native exact ends are evidence, never synthesized here.
    }

    private fun checkedTag(tag: String): String = tag.also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) }
    private fun JSONObject.integer(key: String): Long = get(key).toString().toLong()
    private fun parseOptions(values: Map<String, String>): RuntimeOptions = RuntimeOptions.parseDraft(values).let {
        requireNotNull(it.options) { it.errors.toString() }
    }
    private fun targetOptions(source: RuntimeOptions, overrides: Map<String, String>): RuntimeOptions {
        require(overrides.keys.all { it in labelKeys }) { "Only label-only option_ overrides are supported" }
        return parseOptions(source.toMap() + RuntimeOptions().toMap().filterKeys { it in labelKeys } + overrides)
    }

    private fun readBounded(file: File, limit: Int): String {
        require(file.isFile && file.length() <= limit) { "Missing or oversized ${file.name}" }
        // Bound the read itself as well: a concurrent capture cannot grow past the checked file size.
        file.inputStream().use { input ->
            val bytes = ByteArray(limit + 1)
            var size = 0
            while (size < bytes.size) {
                val n = input.read(bytes, size, bytes.size - size)
                if (n < 0) break
                size += n
            }
            require(size <= limit) { "Growing evidence file ${file.name}" }
            return String(bytes, 0, size, Charsets.UTF_8)
        }
    }

    companion object {
        private const val MAX_SAMPLES = 10 * 60 * 16000L
        private val labelKeys = setOf("speaker_threshold", "speaker_margin", "speaker_confirmation_ms", "unknown_bridge_ms",
            "label_lookahead_ms", "alignment_tolerance_ms", "strong_speaker_threshold", "strong_speaker_margin",
            "strong_confirmation_ms", "word_fallback_ms")
    }
}

/** Pure test-only model of CrispASR 966561aa596c n3d_stream_drain, returning newly available rows. */
private class NativeFrameSchedule(options: RuntimeOptions) {
    private val c = when (options.diarizationMode) { "low_latency" -> 9; "ultra_low_latency" -> 3; else -> 6 }
    private val r = when (options.diarizationMode) { "low_latency" -> 4; "ultra_low_latency" -> 1; else -> 2 }
    private val batch = options.diarizationBatch
    private var scored = 0
    private var samples = 0L
    private var finished = false

    fun advance(totalSamples: Long, final: Boolean): Int {
        require(!finished && totalSamples in samples..(10 * 60 * 16000L))
        samples = totalSamples
        val previous = scored
        // F=8, hop=160, win=400, nfft=512: first has +40; later has 400-256=144.
        // Largest complete batch wins, then drain AGAIN; the cap never defers a complete base chunk.
        while (true) {
            val base = if (scored == 0) 40L else scored * 160L + 144
            val k = ((totalSamples - base - r * 1280L) / (c * 1280L)).coerceIn(0, batch.toLong()).toInt()
            if (k == 0) break
            scored += k * c * 8
        }
        if (final) scored = if (scored == 0) (totalSamples / 160).toInt()
            else maxOf(scored, ((totalSamples - 96) / 160).toInt())
        finished = final
        return scored - previous
    }
}
