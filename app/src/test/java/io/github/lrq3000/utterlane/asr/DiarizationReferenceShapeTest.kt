package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic shapes of the reported regressions, not copies of private audio/text or model evidence. */
class DiarizationReferenceShapeTest {
    private class Script(private val duration: Int, val starts: IntArray, private val twoVoices: Boolean,
                         private val lag: Int) : RecognitionBackend, SpeakerProbabilityStream {
        val words = Array(starts.size) { i -> " ${if (i < starts.size / 2) "mot" else "word"}$i" }
        private val onsets = starts.map { it / 160 }.toHashSet()
        var asrCalls = 0
        var pushedSamples = 0
        private var scored = 0

        override fun transcribeWindow(samples: ShortArray): WindowResult {
            asrCalls++
            val start = (samples.first().toInt() - 1000) * 160
            val selected = starts.indices.filter { starts[it] in start until start + samples.size }
            return WindowResult(selected.map { words[it] }.toTypedArray(),
                selected.map { (starts[it] - start) / 16000f }.toFloatArray(),
                // A reference word belongs to one turn. End it at the true
                // turn boundary instead of manufacturing a mixed-voice word.
                ends = selected.map {
                    val turnEnd = if (twoVoices) (starts[it] / 128000 + 1) * 128000 else duration
                    (minOf(starts[it] + 1920, duration, turnEnd) - start) / 16000f
                }.toFloatArray())
        }

        override fun push(samples: ShortArray, final: Boolean): FloatArray {
            pushedSamples += samples.size
            // Native releases absolute 10 ms frames, with a <=256-sample FFT
            // tail at EOF. Each lexical onset itself is deliberately unvoiced.
            val stop = if (final) (pushedSamples - 128) / 160 else (pushedSamples - lag).coerceAtLeast(0) / 160
            val result = FloatArray((stop - scored) * 8) { i ->
                val frame = scored + i / 8
                val voice = if (twoVoices && frame / 800 % 2 == 1) 4 else 7
                if (frame !in onsets && i % 8 == voice) .95f else .01f
            }
            scored = stop
            return result
        }

        override fun close() {}
    }

    private suspend fun transcript(script: Script, duration: Int, block: Int, count: Int,
                                   options: RuntimeOptions): Pair<String, Int> {
        val formatter = SpeakerText(StreamingCorrections(emptyList())) { if (it < 0) "Unknown speaker" else "Speaker ${it + 1}" }
        val output = mutableListOf<String>()
        DiarizedWindowProcessor(script, script, count, options = options).use { processor ->
            val segmenter = AudioSegmenter(flushPendingOnFinish = true, options = options) { output += formatter.accept(processor.process(it)) }
            for (start in 0 until duration step block) {
                segmenter.accept(ShortArray(minOf(block, duration - start)) { (1000 + (start + it) / 160).toShort() })
            }
            segmenter.finish()
            output += formatter.finish()
        }
        assertEquals(duration, script.pushedSamples)
        return output.filter { it.isNotBlank() }.joinToString(" ") to script.asrCalls
    }

    private fun plain(text: String) = text.replace(Regex("(?:Speaker \\d+|Unknown speaker): ?"), "")
        .replace(Regex("\\s+"), " ").trim()

    @Test fun autoSingleton2545SecondsKeepsFirstWordAllTwentyFiveTailWordsAndOneIdentity(): Unit = runBlocking {
        val duration = 407200
        val starts = IntArray(128) { i -> if (i < 103) i * 3100 else 320000 + (i - 103) * 3620 }
        for (block in listOf(137, duration)) {
            val script = Script(duration, starts, false, 16640)
            val (text, calls) = transcript(script, duration, block, 0, RuntimeOptions(diarizationMode = "low_latency"))
            assertEquals(script.words.joinToString(" ") { it.trim() }, plain(text))
            assertEquals(1, Regex("Speaker \\d+:").findAll(text).count())
            assertFalse(text, text.contains("Unknown"))
            assertEquals(3, calls)
        }
    }

    @Test fun sixTurns4683SecondsKeep128WordsWithMaximumLookaheadAndCatchup(): Unit = runBlocking {
        val duration = 749280
        val starts = IntArray(128) { it * (duration - 320) / 127 }
        val options = RuntimeOptions(diarizationMode = "low_latency", diarizationBatch = 16, labelLookaheadMs = 10000)
        for (count in listOf(0, 2)) {
            // Catch-up is a maximum for ALREADY buffered complete steps, not a
            // request to wait for 16 chunks. Native residual lag stays <= one
            // low-latency chunk/context plus its FFT scheduling allowance.
            val script = Script(duration, starts, true, 1060 * 16)
            val (text, calls) = transcript(script, duration, 997, count, options)
            assertEquals(script.words.joinToString(" ") { it.trim() }, plain(text))
            val labels = Regex("Speaker (\\d+):").findAll(text).map { it.groupValues[1].toInt() }.toList()
            assertEquals(if (count == 0) listOf(8, 5, 8, 5, 8, 5) else listOf(1, 2, 1, 2, 1, 2), labels)
            assertFalse(text, text.contains("Unknown"))
            assertEquals(5, calls)
        }
    }
}
