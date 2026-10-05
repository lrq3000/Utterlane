package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.transcribe.StreamingResampler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.toList
import org.junit.Assert.*
import org.junit.Test
import android.speech.SpeechRecognizer

class AudioPipelineTest {
    @Test fun interruptedFinalWindowCanBeFinishedWithoutLosingOrDuplicatingTail() = runBlocking {
        val delivered = mutableListOf<AudioWindow>()
        var interrupted = true
        val segmenter = AudioSegmenter(sampleRate = 100, maxSeconds = 1, contextSeconds = 0.1) { window ->
            if (interrupted) throw IllegalStateException("worker temporarily unavailable")
            delivered.add(window)
        }
        segmenter.accept(shortArrayOf(1000, 2000, 3000))
        try { segmenter.finish(); fail("Expected interruption") } catch (_: IllegalStateException) { }
        interrupted = false
        segmenter.finish()
        segmenter.finish()
        assertEquals("The uncommitted final audio window must survive interruption", 1, delivered.size)
        assertArrayEquals(shortArrayOf(1000, 2000, 3000), delivered.single().samples)
    }

    @Test fun subwordPiecesAcrossOwnershipBoundaryRemainOneWord() {
        val first = WindowText.select(arrayOf(" Hel", "lo", " world"), floatArrayOf(0.9f, 1.1f, 1.3f), AudioWindow(ShortArray(200), 0, 0, 100), 100)
        val second = WindowText.select(arrayOf(" Hello", " world"), floatArrayOf(0.9f, 1.3f), AudioWindow(ShortArray(200), 0, 100, 200), 100)
        assertEquals("Hello world", "$first $second")
    }

    @Test fun standaloneWhitespaceBpeStartsOwnedNumericWordsAfterLeftOverlap() {
        val window = AudioWindow(ShortArray(64000), 205790, 221790, 269790)
        val times = floatArrayOf(.88f, 1.36f, 1.52f, 1.68f, 1.84f, 2f, 2.16f, 2.32f, 2.48f)
        for ((number, unit, fraction) in listOf(Triple("1", "h", "30"), Triple("2", "m", "45"))) {
            for (separator in listOf(" ", "\t", "\u00a0")) {
                val tokens = arrayOf(" context", separator, number, unit, ",", separator, number, unit, fraction)
                assertEquals("$number$unit,$separator$number$unit$fraction", WindowText.select(tokens, times, window))
            }
        }
    }

    @Test fun punctuationOwnershipStillUsesItsOriginalWhitespaceBoundary() {
        val window = AudioWindow(ShortArray(32000), 0, 16000, 32000)
        val times = floatArrayOf(.8f, 1.1f, 1.4f)
        assertEquals("! next", WindowText.select(arrayOf(" context", " !", " next"), times, window))
        assertEquals("next", WindowText.select(arrayOf(" context", "!", " next"), times, window))
    }

    @Test fun numericOwnershipRetainsTheStandaloneWhitespaceTimestamp() {
        val window = AudioWindow(ShortArray(32000), 0, 16000, 32000)
        // The numeric word starts with its whitespace BPE token, just as in
        // the original Off path, even when later digit pieces cross the cut.
        assertEquals("next", WindowText.select(arrayOf(" context", " ", "4", "2", " next"),
            floatArrayOf(.8f, .96f, 1.04f, 1.12f, 1.5f), window))
    }

    @Test fun unicodePagesExcludePartialUtf8Characters() {
        val directory = java.nio.file.Files.createTempDirectory("unicode-pages").toFile()
        val store = TranscriptStore(java.io.File(directory, "result.txt"))
        try {
            store.append("é🙂".repeat(4000))
            assertFalse(store.page(1, 17).contains('\uFFFD'))
            assertFalse(store.preview().first().isLowSurrogate())
        } finally { store.dispose(); directory.deleteRecursively() }
    }
    @Test fun disposingTranscriptWaitsForAnExportLease() {
        val directory = java.nio.file.Files.createTempDirectory("transcript-lease").toFile()
        try {
            val store = TranscriptStore(java.io.File(directory, "result.txt"))
            store.append("keep until export is ready")
            val lease = store.acquire()
            store.dispose()
            assertTrue(store.file.exists())
            lease.close()
            assertFalse(store.file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun periodicCacheCleanupProtectsActiveAndExpiresReleasedResults() {
        val directory = java.nio.file.Files.createTempDirectory("transcript-prune").toFile()
        try {
            val store = TranscriptStore(java.io.File(directory, "result.txt"))
            store.append("active")
            assertTrue(store.file.setLastModified(1000))
            CacheArtifacts.prune(directory, 5000, 10000)
            assertTrue(store.file.exists())
            store.keepForRecovery()
            CacheArtifacts.prune(directory, 5000, 10000)
            assertFalse(store.file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun speechFailureCategoriesAreNotCollapsedIntoAudioErrors() {
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, SessionFailure(SessionFailure.Kind.NO_SPEECH, "silence").recognitionError())
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SessionFailure(SessionFailure.Kind.BUSY, "busy").recognitionError())
        assertEquals(SpeechRecognizer.ERROR_SERVER, SessionFailure(SessionFailure.Kind.MODEL, "model").recognitionError())
        assertEquals(SpeechRecognizer.ERROR_AUDIO, SessionFailure(SessionFailure.Kind.AUDIO, "capture").recognitionError())
    }
    @Test fun reopeningTranscriptRestoresItsBoundedPreview() {
        val directory = java.nio.file.Files.createTempDirectory("recovery-preview").toFile()
        try {
            val file = java.io.File(directory, "result.txt")
            val original = TranscriptStore(file)
            original.append("recover this text")
            val reopened = TranscriptStore(file)
            assertEquals("recover this text", reopened.preview())
        } finally { directory.deleteRecursively() }
    }

    @Test fun transcriptSnapshotDoesNotChangeAfterAnotherSegment() {
        val directory = java.nio.file.Files.createTempDirectory("export-snapshot").toFile()
        try {
            val original = TranscriptStore(java.io.File(directory, "result.txt"))
            original.append("first segment")
            val snapshot = original.snapshot()
            original.append("later segment")
            assertEquals("first segment", snapshot.readText())
            assertEquals("first segment later segment", original.file.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun microphoneBacklogRejectsOverflowWithoutGrowing() = runBlocking {
        val queue = BoundedAudioQueue(2)
        assertTrue(queue.offer(ShortArray(3200)))
        assertTrue(queue.offer(ShortArray(3200)))
        assertFalse(queue.offer(ShortArray(3200)))
        assertEquals(3200, queue.blocks.receive().size)
        assertTrue(queue.offer(ShortArray(3200)))
        queue.close()
        assertEquals(2, queue.blocks.toList().size)
    }

    @Test fun correctionPhraseSpanningWindowsIsEmittedOnce() {
        val processor = StreamingCorrections(listOf(DictionaryManager.ReplacementRule("New York", "NYC")))
        val output = listOf(processor.accept("visit New"), processor.accept("York tomorrow"), processor.finish()).filter { it.isNotEmpty() }.joinToString(" ")
        assertEquals("visit NYC tomorrow", output)
    }
    @Test fun longSourceHasBoundedWindowsAndDisjointOwnership() = runBlocking {
        var expectedStart = 0L
        var count = 0
        val segmenter = AudioSegmenter(sampleRate = 100, maxSeconds = 1, contextSeconds = 0.1) { window ->
            assertTrue(window.samples.size <= 120)
            assertEquals(expectedStart, window.ownedStart)
            assertTrue(window.ownedEnd > window.ownedStart)
            assertTrue(window.ownedEnd - window.ownedStart <= 100)
            expectedStart = window.ownedEnd
            count++
        }
        repeat(1000) { segmenter.accept(ShortArray(137) { 1000 }) }
        segmenter.finish()
        assertEquals(137000L, expectedStart)
        assertTrue(count >= 1370)
    }

    @Test fun shortTailAndEmptyInputAreFlushedExactlyOnce() = runBlocking {
        val windows = mutableListOf<AudioWindow>()
        val segmenter = AudioSegmenter(sampleRate = 100, maxSeconds = 1, contextSeconds = 0.1) { windows.add(it) }
        segmenter.accept(shortArrayOf(1000, 2000, 3000))
        segmenter.finish()
        segmenter.finish()
        assertEquals(1, windows.size)
        assertArrayEquals(shortArrayOf(1000, 2000, 3000), windows.single().samples)
    }

    @Test fun resamplingIsIndependentOfBufferBoundaries() {
        val input = FloatArray(4801 * 2) { (it / 2 % 100) / 100f }
        fun convert(split: Boolean): ShortArray {
            val converter = StreamingResampler(48000, 2)
            val output = mutableListOf<Short>()
            if (split) {
                var offset = 0
                while (offset < input.size) {
                    val end = minOf(offset + 127, input.size)
                    output.addAll(converter.accept(input.copyOfRange(offset, end)).toList())
                    offset = end
                }
            } else output.addAll(converter.accept(input).toList())
            output.addAll(converter.finish().toList())
            return output.toShortArray()
        }
        assertArrayEquals(convert(false), convert(true))
    }

    @Test fun fractionalRateConversionPreservesDuration() {
        val converter = StreamingResampler(44100, 1)
        val result = converter.accept(FloatArray(44100) { 0.25f }) + converter.finish()
        assertEquals(16000, result.size)
        assertTrue(result.all { it == 8192.toShort() })
    }

    @Test fun timestampOwnershipDoesNotRemoveRealRepetitions() {
        val window = AudioWindow(ShortArray(120), 0, 20, 100)
        val result = WindowText.select(
            arrayOf(" context", " hello", " hello", ".", " future"),
            floatArrayOf(0.1f, 0.3f, 0.7f, 0.7f, 1.1f), window, sampleRate = 100
        )
        assertEquals("hello hello.", result.trim())
    }

    @Test fun transcriptPreviewIsBoundedButExportIsComplete() {
        val directory = java.nio.file.Files.createTempDirectory("transcript-test").toFile()
        try {
            val store = TranscriptStore(java.io.File(directory, "result.txt"))
            repeat(5000) { store.append("hello") }
            assertTrue(store.preview().length <= TranscriptStore.PREVIEW_LIMIT)
            assertNull(store.readForTransfer(100))
            assertEquals(29999L, store.file.length())
            assertEquals(5000, store.file.readText().split(' ').size)
        } finally { directory.deleteRecursively() }
    }
}
