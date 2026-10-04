package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test

class DiarizedWindowProcessorTest {
    @Test fun finalCenteredFftTailKeepsItsLastSpeakerWithoutDroppingText() {
        val stream = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean) = FloatArray(383 * 8) { if (it % 8 == 1) .95f else .01f }
            override fun close() {}
        }
        val asr = object : RecognitionBackend {
            override fun transcribeWindow(samples: ShortArray) = WindowResult(arrayOf(" ending"), floatArrayOf(61300 / 16000f))
            override fun close() {}
        }
        DiarizedWindowProcessor(asr, stream, 0).use { processor ->
            // Reproduced on Android: 61,522 PCM samples, 61,280 labeled samples.
            assertEquals(listOf(SpeechSpan("ending", 1)), processor.process(AudioWindow(ShortArray(61522), 0, 0, 61522, true)))
        }
    }
    @Test fun overlapsAreFedOnlyOnceAndLastWindowFlushes() {
        val feed = mutableListOf<Int>()
        var flushed = false
        val stream = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean): FloatArray {
                feed += samples.size; flushed = final
                return FloatArray(samples.size / 160 * 8) { if (it % 8 == 0) .9f else .01f }
            }
            override fun close() {}
        }
        val asr = object : RecognitionBackend {
            override fun transcribeWindow(samples: ShortArray) = WindowResult(arrayOf(" hello"), floatArrayOf(1.5f))
            override fun close() {}
        }
        DiarizedWindowProcessor(asr, stream, 0).use { processor ->
            val a = processor.process(AudioWindow(ShortArray(64000), 0, 0, 48000))
            val b = processor.process(AudioWindow(ShortArray(64000), 32000, 48000, 96000, true))
            assertEquals(listOf(64000, 32000), feed)
            assertTrue(flushed)
            assertEquals(listOf(SpeechSpan("hello", 0)), a)
            assertEquals(listOf(SpeechSpan("hello", 0)), b)
        }
    }
    @Test fun textOnlyBackendTranscribesDisjointSpeakerTurns() {
        val stream = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean) = FloatArray(200 * 8) { i ->
                if (i % 8 == (i / 8 / 100)) .95f else .01f
            }
            override fun close() {}
        }
        val lengths = mutableListOf<Int>()
        val asr = object : RecognitionBackend {
            override fun transcribeWindow(samples: ShortArray): WindowResult {
                lengths += samples.size
                return WindowResult(emptyArray(), floatArrayOf(), "voice ${lengths.size}")
            }
            override fun close() {}
        }
        DiarizedWindowProcessor(asr, stream, 0, textOnly = true).use { processor ->
            val spans = processor.process(AudioWindow(ShortArray(32000), 0, 0, 32000, true))
            assertEquals(listOf(16000, 16000), lengths)
            assertEquals(listOf(0, 1), spans.map { it.speaker })
        }
    }
}
