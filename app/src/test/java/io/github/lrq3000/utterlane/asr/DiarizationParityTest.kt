package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Public synthetic words/times only: no recordings, native libraries, or private reference text. */
class DiarizationParityTest {
    private data class Word(val text: String, val at: Int)
    private val words = listOf(
        Word(" visit", 1600), Word(" New", 15680), Word(" York", 16320),
        Word(" today.", 24000), Word(" Hein?", 32000), Word(" Yes!", 34000),
        Word(" first", 36800), Word("  second", 47200), Word(" and", 50000),
        Word(" final", 73600)
    )
    private val rules = listOf(DictionaryManager.ReplacementRule("New York", "NYC"),
        DictionaryManager.ReplacementRule("first  second", "together"))

    private inner class Backend : RecognitionBackend {
        var calls = 0
        override fun transcribeWindow(samples: ShortArray): WindowResult {
            calls++
            val start = (samples.first().toInt() - 1000) * 160
            val selected = words.filter { it.at in start until start + samples.size }
            return WindowResult(selected.map { it.text }.toTypedArray(),
                selected.map { (it.at - start) / 16000f }.toFloatArray(),
                ends = selected.map { (it.at - start + 1280) / 16000f }.toFloatArray())
        }
        override fun close() {}
    }

    private class LaggingStream : SpeakerProbabilityStream {
        var fed = 0
        var scored = 0
        override fun push(samples: ShortArray, final: Boolean): FloatArray {
            fed += samples.size
            val end = if (final) fed / 160 else ((fed - 16640).coerceAtLeast(0) / 160)
            val output = FloatArray((end - scored) * 8) { i ->
                val frame = scored + i / 8
                val speaker = if (frame in 200..211 || frame >= 290) 1 else 0
                if (i % 8 == speaker) .95f else .01f
            }
            scored = end
            return output
        }
        override fun close() {}
    }

    private fun plain(text: String) = text.replace(Regex("(?:Speaker \\d+|Unknown speaker): ?"), "")
        .replace(Regex("\\s+"), " ").trim()

    @Test fun strippingLabelsMatchesOffCorrectionsDespiteLagSpeakerAndWindowBoundaries(): Unit = runBlocking {
        val base = RuntimeOptions(asrWindowSeconds = 1.0, asrMinSeconds = 1.0,
            asrLeftContextSeconds = .2, asrRightContextSeconds = .2, diarizationMode = "low_latency")
        val audio = ShortArray(74400) { (1000 + it / 160).toShort() }
        suspend fun transcribe(labeled: Boolean, block: Int, options: RuntimeOptions): Pair<String, Int> {
            val backend = Backend()
            val corrections = StreamingCorrections(rules)
            val formatter = SpeakerText(corrections) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
            val output = mutableListOf<String>()
            DiarizedWindowProcessor(backend, LaggingStream(), 0, options = options).use { processor ->
                val segmenter = AudioSegmenter(flushPendingOnFinish = labeled, options = options) { window ->
                    if (labeled) output += formatter.accept(processor.process(window))
                    else {
                        val result = backend.transcribeWindow(window.samples)
                        output += corrections.accept(WindowText.select(result.tokens, result.timestamps, window))
                    }
                }
                for (start in audio.indices step block) segmenter.accept(audio.copyOfRange(start, minOf(start + block, audio.size)))
                segmenter.finish()
                output += if (labeled) formatter.finish() else corrections.finish()
            }
            return plain(output.joinToString(" ")) to backend.calls
        }
        val off = transcribe(false, audio.size, base)
        assertEquals("visit NYC today. Hein? Yes! together and final", off.first)
        for (block in listOf(137, 16000, audio.size)) {
            for (options in listOf(base, base.copy(labelLookaheadMs = 10000), base.copy(speakerThreshold = 1f))) {
                assertEquals(off, transcribe(true, block, options))
            }
        }
    }
}
