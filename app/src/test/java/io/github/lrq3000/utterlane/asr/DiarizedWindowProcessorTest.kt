package io.github.lrq3000.utterlane.asr

import org.junit.Assert.*
import org.junit.Test
import io.github.lrq3000.utterlane.settings.RuntimeOptions

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
    @Test fun textOnlyBackendTranscribesOnceAndKeepsWholeTextWhenTurnsAreAmbiguous() {
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
            assertEquals(listOf(32000), lengths)
            assertEquals(listOf(SpeechSpan("voice 1", -1)), spans)
        }
    }

    private class Backend(private val result: WindowResult) : RecognitionBackend {
        var calls = 0
        override fun transcribeWindow(samples: ShortArray): WindowResult { calls++; return result }
        override fun close() {}
    }

    private class Frames(private val labels: IntArray) : SpeakerProbabilityStream {
        var fed = 0
        var calls = 0
        override fun push(samples: ShortArray, final: Boolean): FloatArray {
            calls++
            val start = fed / 160
            fed += samples.size
            return FloatArray((fed / 160 - start) * 8) { i ->
                if (i % 8 == labels.getOrElse(start + i / 8) { -1 }) .95f else .01f
            }
        }
        override fun close() {}
    }

    @Test fun unvoicedOnsetUsesVoicedWordBodyAndPunctuationFollowsTheWord() {
        val asr = Backend(WindowResult(arrayOf(" Hello", " world", " !"), floatArrayOf(0f, .5f, 1.5f)))
        val stream = Frames(IntArray(200) { if (it in 5..120) 0 else -1 })
        DiarizedWindowProcessor(asr, stream, 0).use { processor ->
            assertEquals(listOf(SpeechSpan("Hello world !", 0)),
                processor.process(AudioWindow(ShortArray(32000), 0, 0, 32000, true)))
        }
        assertEquals(1, asr.calls)
    }

    @Test fun autoKeepsSixTurnsAndReturningIdentitiesIncludingBriefInterjection() {
        val starts = floatArrayOf(0f, .5f, 1f, 1.12f, 1.7f, 2.2f)
        val asr = Backend(WindowResult(arrayOf(" Bonjour", " hello", " Hein?", " yes", " retour", " bye"), starts))
        val stream = Frames(IntArray(280) { when (it) {
            in 0..49 -> 0; in 50..99 -> 1; in 100..111 -> 0
            in 112..169 -> 1; in 170..219 -> 0; else -> 1
        } })
        DiarizedWindowProcessor(asr, stream, 0).use { processor ->
            val spans = processor.process(AudioWindow(ShortArray(44800), 0, 0, 44800, true))
            assertEquals(listOf(0, 1, 0, 1, 0, 1), spans.map { it.speaker })
            assertEquals("Bonjour hello Hein? yes retour bye", spans.joinToString(" ") { it.text.trim() })
        }
    }

    @Test fun fixedOneNeverInvokesSpeakerInferenceAndKeepsAllWords() {
        val asr = Backend(WindowResult(arrayOf(" First", " last"), floatArrayOf(0f, 1.9f)))
        val stream = Frames(IntArray(200) { -1 })
        DiarizedWindowProcessor(asr, stream, 1).use { processor ->
            assertEquals(listOf(SpeechSpan("First last", 0)),
                processor.process(AudioWindow(ShortArray(32000), 0, 0, 32000, true)))
        }
        assertEquals(0, stream.calls)
        assertEquals(1, asr.calls)
    }

    @Test fun nativeLagCarriesTailToNextWindowInsteadOfThrowingOrDroppingWords() {
        var calls = 0
        val stream = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean): FloatArray {
                calls++
                return FloatArray((if (calls == 1) 150 else 350) * 8) { if (it % 8 == 0) .95f else .01f }
            }
            override fun close() {}
        }
        val asr = Backend(WindowResult(arrayOf(" first", " last"), floatArrayOf(0f, 1.9f)))
        DiarizedWindowProcessor(asr, stream, 0).use { processor ->
            val a = processor.process(AudioWindow(ShortArray(48000), 0, 0, 32000))
            val b = processor.process(AudioWindow(ShortArray(48000), 32000, 32000, 80000, true))
            assertEquals("first last first last", (a + b).joinToString(" ") { it.text.trim() })
            assertTrue((a + b).all { it.speaker == 0 })
            assertEquals(2, asr.calls)
        }
    }

    @Test fun boundedUnknownGapNeedsAgreementAndSingleFrameJitterCannotCreateLabel() {
        fun run(middle: IntArray): List<SpeechSpan> {
            val labels = IntArray(100) { 0 } + middle + IntArray(100) { 0 }
            val asr = Backend(WindowResult(arrayOf(" before", " middle", " after"), floatArrayOf(.5f, 1f, 1.2f)))
            return DiarizedWindowProcessor(asr, Frames(labels), 0).use {
                it.process(AudioWindow(ShortArray(labels.size * 160), 0, 0, labels.size * 160L, true))
            }
        }
        assertEquals(listOf(SpeechSpan("before middle after", 0)), run(IntArray(20) { -1 }))
        assertEquals(listOf(0, 1, 0), run(IntArray(20) { 1 }).map { it.speaker })
        assertEquals(listOf(SpeechSpan("before middle after", 0)), run(IntArray(20) { if (it == 0) 1 else -1 }))
    }

    @Test fun nativeEndsPreventBorrowingTheFollowingVoiceAndPreserveWhitespace() {
        val result = WindowResult(arrayOf(" First", "  second", "."), floatArrayOf(0f, .5f, 1f),
            ends = floatArrayOf(.1f, .9f, 1f))
        val stream = Frames(IntArray(150) { if (it < 10) 0 else 1 })
        DiarizedWindowProcessor(Backend(result), stream, 0).use { processor ->
            assertEquals(listOf(SpeechSpan("First", 0), SpeechSpan("  second.", 1)),
                processor.process(AudioWindow(ShortArray(24000), 0, 0, 24000, true)))
        }
        DiarizedWindowProcessor(Backend(result), Frames(IntArray(150) { 0 }), 0).use { processor ->
            assertEquals(listOf(SpeechSpan("First  second.", 0)),
                processor.process(AudioWindow(ShortArray(24000), 0, 0, 24000, true)))
        }
    }

    @Test fun maximumLabelLookaheadRetainsNeededHistoryAcrossManyWindows() {
        val stream = Frames(IntArray(3000) { 0 })
        val asr = Backend(WindowResult(arrayOf(" word"), floatArrayOf(0f)))
        val output = mutableListOf<SpeechSpan>()
        DiarizedWindowProcessor(asr, stream, 0, options = RuntimeOptions(labelLookaheadMs = 10000)).use { processor ->
            repeat(30) { i ->
                output += processor.process(AudioWindow(ShortArray(16000), i * 16000L, i * 16000L,
                    (i + 1) * 16000L, i == 29))
            }
        }
        assertEquals(30, output.sumOf { it.text.split(' ').size })
        assertTrue(output.all { it.speaker == 0 })
    }

    @Test fun genericTimedWordsUseOnePassEvenWhenTextOnlyFlagIsSet() {
        val asr = Backend(WindowResult(arrayOf(" one", " two"), floatArrayOf(0f, 1f), "one two"))
        DiarizedWindowProcessor(asr, Frames(IntArray(200) { it / 100 }), 0, textOnly = true).use {
            assertEquals(listOf(0, 1), it.process(AudioWindow(ShortArray(32000), 0, 0, 32000, true)).map { it.speaker })
        }
        assertEquals(1, asr.calls)
    }

    @Test fun untimedTextCannotHideABriefRealTurnInsideTheMajoritySpeaker() {
        val asr = Backend(WindowResult(emptyArray(), floatArrayOf(), "The whole recognized text stays."))
        DiarizedWindowProcessor(asr, Frames(IntArray(200) { if (it in 100..111) 1 else 0 }), 0).use {
            assertEquals(listOf(SpeechSpan("The whole recognized text stays.", -1)),
                it.process(AudioWindow(ShortArray(32000), 0, 0, 32000, true)))
        }
    }

    @Test fun finalNativeShortfallFlushesEveryWordWithoutInventingTailIdentity() {
        val stream = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean) = FloatArray(100 * 8) { if (it % 8 == 0) .95f else .01f }
            override fun close() {}
        }
        val asr = Backend(WindowResult(arrayOf(" first", " final"), floatArrayOf(0f, 1.9f)))
        DiarizedWindowProcessor(asr, stream, 0).use {
            val spans = it.process(AudioWindow(ShortArray(32000), 0, 0, 32000, true))
            assertEquals(listOf(0, -1), spans.map { span -> span.speaker })
            assertEquals("first final", spans.joinToString(" ") { span -> span.text.trim() })
        }
    }

    @Test fun genericWordArraysKeepFullRawTextSpacingPunctuationAndContextLikeOff() {
        val result = WindowResult(arrayOf("Hello", "world", "keep", "everything"),
            floatArrayOf(0f, .5f, 1f, 1.5f), "Hello,  world! keep everything.")
        DiarizedWindowProcessor(Backend(result), Frames(IntArray(200) { 0 }), 0, textOnly = true).use {
            // Generic Off uses result.text, including context. Optional arrays
            // must not silently remove its leading/trailing recognized words.
            assertEquals(listOf(SpeechSpan(result.text!!, 0)),
                it.process(AudioWindow(ShortArray(32000), 0, 8000, 24000, true)))
        }
    }

    @Test fun incompleteGenericArraysFallBackToWholeRawTextInsteadOfLosingUnalignedWords() {
        val result = WindowResult(arrayOf("keep"), floatArrayOf(0f), "keep these final words")
        DiarizedWindowProcessor(Backend(result), Frames(IntArray(200) { 0 }), 0, textOnly = true).use {
            assertEquals(listOf(SpeechSpan(result.text!!, 0)),
                it.process(AudioWindow(ShortArray(32000), 0, 0, 32000, true)))
        }
    }

    @Test fun genericWordsWithoutLeadingSpacesCanStillAttributeRealTurns() {
        val result = WindowResult(arrayOf("Hello", "again"), floatArrayOf(0f, 1f), "Hello again!")
        DiarizedWindowProcessor(Backend(result), Frames(IntArray(200) { it / 100 }), 0, textOnly = true).use {
            assertEquals(listOf(SpeechSpan("Hello", 0), SpeechSpan(" again!", 1)),
                it.process(AudioWindow(ShortArray(32000), 0, 0, 32000, true)))
        }
    }

    @Test fun trailingTokenWhitespaceIsPartOfTheSameBaselineText() {
        val result = WindowResult(arrayOf(" one ", " two"), floatArrayOf(0f, 1f))
        DiarizedWindowProcessor(Backend(result), Frames(IntArray(200) { 0 }), 0).use {
            val window = AudioWindow(ShortArray(32000), 0, 0, 32000, true)
            assertEquals(listOf(SpeechSpan(WindowText.select(result.tokens, result.timestamps, window), 0)), it.process(window))
        }
    }

    @Test fun closeDiscardsPendingMetadataAndNeverPushesAgain() {
        var pushes = 0
        var closes = 0
        val stream = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean): FloatArray { pushes++; return floatArrayOf() }
            override fun close() { closes++ }
        }
        val asr = Backend(WindowResult(arrayOf(" pending"), floatArrayOf(0f)))
        val processor = DiarizedWindowProcessor(asr, stream, 0)
        assertTrue(processor.process(AudioWindow(ShortArray(16000), 0, 0, 16000)).isEmpty())
        processor.close()
        processor.close()
        assertThrows(IllegalStateException::class.java) {
            processor.process(AudioWindow(ShortArray(16000), 16000, 16000, 32000, true))
        }
        assertEquals(1, pushes)
        assertEquals(1, asr.calls)
        assertEquals(1, closes)
    }

    @Test fun oversizedUntimedMetadataIsEmittedUnknownInsteadOfRetainedWithoutABound() {
        val text = "x".repeat(262145)
        val stream = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean) = floatArrayOf()
            override fun close() {}
        }
        DiarizedWindowProcessor(Backend(WindowResult(emptyArray(), floatArrayOf(), text)), stream, 0).use {
            assertEquals(listOf(SpeechSpan(text, -1)), it.process(AudioWindow(ShortArray(16000), 0, 0, 16000)))
        }
    }

    @Test fun stalledNativeOutputExpiresMetadataAndFinalOversizedBatchStillKeepsAllWords() {
        var fed = 0
        val stream = object : SpeakerProbabilityStream {
            override fun push(samples: ShortArray, final: Boolean): FloatArray {
                fed += samples.size
                return if (final) FloatArray(fed / 160 * 8) { if (it % 8 == 0) .95f else .01f } else floatArrayOf()
            }
            override fun close() {}
        }
        val output = mutableListOf<SpeechSpan>()
        val asr = Backend(WindowResult(arrayOf(" word"), floatArrayOf(0f)))
        DiarizedWindowProcessor(asr, stream, 0).use { processor ->
            repeat(100) { i ->
                output += processor.process(AudioWindow(ShortArray(16000), i * 16000L, i * 16000L,
                    (i + 1) * 16000L, i == 99))
                assertTrue("Never retain words in proportion to recording length", output.sumOf { it.text.trim().split(' ').size } >= i - 2)
            }
        }
        assertEquals(100, output.sumOf { it.text.trim().split(' ').size })
        assertEquals(-1, output.first().speaker)
        assertEquals(0, output.last().speaker)
        assertEquals(100, asr.calls)
    }
}
