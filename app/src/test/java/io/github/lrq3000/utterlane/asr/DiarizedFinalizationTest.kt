package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiarizedFinalizationTest {
    @Test fun eofFlushPreservesOffAsrPcmAndOwnershipAtExactBoundaryAfterBoundaryAndShortTail(): Unit = runBlocking {
        for (length in listOf(160000, 168000, 8000)) for (textOnly in listOf(false, true)) {
            val audio = ShortArray(length) { (1000 + it / 16).toShort() }
            val starts = listOf(0, 158400, length - 800).filter { it < length }.distinct().sorted()
            val off = RecordingBackend(starts, textOnly)
            val on = RecordingBackend(starts, textOnly)
            val offWindows = mutableListOf<AudioWindow>()
            val onWindows = mutableListOf<AudioWindow>()
            val expected = mutableListOf<String>()
            val ordinary = AudioSegmenter { window ->
                offWindows += window
                val result = off.transcribeWindow(if (textOnly) window.samples.copyOfRange(
                    (window.ownedStart - window.startSample).toInt(), (window.ownedEnd - window.startSample).toInt()) else window.samples)
                expected += result.text ?: WindowText.select(result.tokens, result.timestamps, window)
            }
            val stream = DelayedFrames()
            val actual = mutableListOf<SpeechSpan>()
            DiarizedWindowProcessor(on, stream, 0, textOnly).use { processor ->
                val labeled = AudioSegmenter(flushPendingOnFinish = true) { window ->
                    onWindows += window
                    actual += processor.process(window)
                }
                ordinary.accept(audio)
                labeled.accept(audio)
                ordinary.finish()
                labeled.finish()
                assertEquals("length=$length custom=$textOnly", offWindows.size, onWindows.size)
                offWindows.zip(onWindows).forEach { (a, b) ->
                    assertEquals(a.startSample, b.startSample)
                    assertEquals(a.ownedStart, b.ownedStart)
                    assertEquals(a.ownedEnd, b.ownedEnd)
                    assertArrayEquals(a.samples, b.samples)
                }
                assertEquals(off.calls.size, on.calls.size)
                off.calls.zip(on.calls).forEach { (a, b) -> assertArrayEquals(a, b) }
                assertEquals(expected.filter { it.isNotBlank() }.joinToString(" "), actual.joinToString(" ") { it.text.trim() })
                assertEquals(0, actual.last().speaker)
                assertEquals(length, stream.fed)
                assertEquals(1, stream.pushes.count { it.second })
                assertTrue(onWindows.last().isFinal)
                if (length == 168000) assertEquals(listOf(168000 to false, 0 to true), stream.pushes)
            }
        }
    }

    @Test fun zeroRightContextExactEofDrainsNativeWithEmptyPcmAndNeverRecognizesAgain(): Unit = runBlocking {
        val options = RuntimeOptions(asrRightContextSeconds = 0.0)
        val backend = RecordingBackend(listOf(159200), false)
        val stream = DelayedFrames()
        val spans = mutableListOf<SpeechSpan>()
        DiarizedWindowProcessor(backend, stream, 0, options = options).use { processor ->
            val segmenter = AudioSegmenter(flushPendingOnFinish = true, options = options) { spans += processor.process(it) }
            segmenter.accept(ShortArray(160000) { (1000 + it / 16).toShort() })
            assertEquals(1, backend.calls.size)
            assertTrue(spans.isEmpty())
            segmenter.finish()
            assertEquals(listOf(160000 to false), stream.pushes)
            spans += processor.finish()
            assertEquals(listOf(SpeechSpan("word0", 0)), spans)
            assertTrue(processor.finish().isEmpty())
            assertEquals(listOf(160000 to false, 0 to true), stream.pushes)
            assertEquals(1, backend.calls.size)
        }
    }

    @Test fun explicitFinishExtendsOnlyTheDocumentedCenteredFftTail() {
        for ((shortfall, speaker) in listOf(242 to 0, 402 to -1)) {
            val backend = RecordingBackend(listOf(61300), false)
            val stream = DelayedFrames(shortfall)
            DiarizedWindowProcessor(backend, stream, 0).use { processor ->
                assertTrue(processor.process(AudioWindow(ShortArray(61522) { 1000 }, 0, 0, 61522)).isEmpty())
                assertEquals(listOf(SpeechSpan("word0", speaker)), processor.finish())
                assertTrue(processor.finish().isEmpty())
                assertEquals(1, backend.calls.size)
                assertEquals(listOf(61522 to false, 0 to true), stream.pushes)
            }
        }
    }

    @Test fun finalFlagAndFixedOneNeedNoAdditionalNativeFlush() {
        for (count in listOf(0, 1)) {
            val backend = RecordingBackend(listOf(7200), false)
            val stream = DelayedFrames()
            val processor = DiarizedWindowProcessor(backend, stream, count)
            // Fixed-one also needs no push when its final window was delivered
            // by accept(), without an EOF flag (zero-right-context boundary).
            assertEquals(listOf(SpeechSpan("word0", 0)),
                processor.process(AudioWindow(ShortArray(8000) { 1000 }, 0, 0, 8000, count == 0)))
            assertTrue(processor.finish().isEmpty())
            assertTrue(processor.finish().isEmpty())
            processor.close()
            assertTrue(processor.finish().isEmpty())
            assertEquals(if (count == 0) listOf(8000 to true) else emptyList(), stream.pushes)
            assertEquals(1, backend.calls.size)
        }
    }

    private class RecordingBackend(private val starts: List<Int>, private val textOnly: Boolean) : RecognitionBackend {
        val calls = mutableListOf<ShortArray>()
        override fun transcribeWindow(samples: ShortArray): WindowResult {
            calls += samples
            val start = (samples.first().toInt() - 1000) * 16
            val indices = starts.indices.filter { starts[it] in start until start + samples.size }
            val words = indices.map { " word$it" }.toTypedArray()
            return WindowResult(words, indices.map { (starts[it] - start) / 16000f }.toFloatArray(),
                if (textOnly) words.joinToString("").trim() else null)
        }
        override fun close() {}
    }

    private class DelayedFrames(private val finalShortfall: Int = 0) : SpeakerProbabilityStream {
        val pushes = mutableListOf<Pair<Int, Boolean>>()
        var fed = 0
        private var frames = 0
        override fun push(samples: ShortArray, final: Boolean): FloatArray {
            pushes += samples.size to final
            fed += samples.size
            val available = (fed - if (final) finalShortfall else 12800).coerceAtLeast(0) / 160
            return FloatArray((available - frames) * 8) { if (it % 8 == 0) .95f else .01f }.also { frames = available }
        }
        override fun close() {}
    }
}
