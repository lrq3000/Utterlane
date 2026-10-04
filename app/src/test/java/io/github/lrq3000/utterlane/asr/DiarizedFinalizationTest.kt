package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiarizedFinalizationTest {
    @Test fun eofAtOrJustAfterPendingBoundaryFlushesBeforeOwnershipIsEmitted(): Unit = runBlocking {
        for (length in listOf(160000, 168000)) {
            val stream = object : SpeakerProbabilityStream {
                override fun push(samples: ShortArray, final: Boolean): FloatArray {
                    // Real streaming inference withholds lookahead until EOF.
                    assertTrue("Pending boundary was decoded without its final flush", final)
                    return FloatArray(samples.size / 160 * 8) { if (it % 8 == 0) .95f else .01f }
                }
                override fun close() {}
            }
            val backend = object : RecognitionBackend {
                override fun transcribeWindow(samples: ShortArray) = WindowResult(arrayOf(" last"), floatArrayOf(9.9f))
                override fun close() {}
            }
            val spans = mutableListOf<SpeechSpan>()
            DiarizedWindowProcessor(backend, stream, 0).use { processor ->
                val segmenter = AudioSegmenter(flushPendingOnFinish = true) { window -> spans += processor.process(window) }
                segmenter.accept(ShortArray(length) { 1000 })
                assertTrue(spans.isEmpty())
                segmenter.finish()
                assertEquals(listOf(SpeechSpan("last", 0)), spans)
            }
        }
    }
}
