package io.github.lrq3000.utterlane.asr

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptionSessionLifecycleTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun speakerEnabledPunctuationOnlyEofDoesNotClaimLabels(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), onSegment = {},
            decode = { error("Unexpected plain decode") }, decodeSpeakers = { emptyList() },
            finishSpeakers = { listOf(SpeechSpan("!", 0)) })
        try {
            session.finish()
            assertEquals("!", store.file.readText())
            assertEquals(1, store.segments)
            assertFalse(session.hasSpeakerLabels)
        } finally { session.close(); store.dispose() }
    }

    @Test fun punctuationBeforeFailedLabeledAppendDoesNotClaimPreparedLabels(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        val committed = java.io.File(directory.root, "committed-prefix.txt")
        // The correction buffer emits punctuation first and holds the lexical
        // tail until finish, providing two distinct real filesystem appends.
        val corrections = StreamingCorrections(listOf(DictionaryManager.ReplacementRule("words", "words")))
        var labelPrepared = false
        val session = TranscriptionSession(store, corrections, onSegment = { text ->
            assertEquals("!", text)
            assertTrue(store.file.renameTo(committed))
            assertTrue(store.file.mkdir()) // Make the next append fail without mocking storage.
        }, decode = { error("Unexpected plain decode") }, decodeSpeakers = { emptyList() },
            finishSpeakers = { listOf(SpeechSpan("! words", 0)) },
            speakerLabel = { labelPrepared = true; "Voix α" })
        try {
            assertThrows(java.io.IOException::class.java) { runBlocking { session.finish() } }
            assertTrue("The failure must occur after a labeled emission was prepared", labelPrepared)
            assertEquals("!", committed.readText())
            assertEquals(1, store.segments)
            assertFalse(session.hasSpeakerLabels)
        } finally {
            session.close()
            if (store.file.isDirectory) { store.file.delete(); committed.renameTo(store.file) }
            store.dispose()
        }
    }

    @Test fun committedLocalizedLabelIsVisibleBeforeAFailingDeliveryCallback(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        lateinit var session: TranscriptionSession
        session = TranscriptionSession(store, StreamingCorrections(emptyList()), onSegment = {
            assertTrue(session.hasSpeakerLabels)
            error("delivery failed")
        }, decode = { error("Unexpected plain decode") }, decodeSpeakers = { emptyList() },
            finishSpeakers = { listOf(SpeechSpan("words", -1)) }, speakerLabel = { "Voix inconnue" })
        try {
            val failure = assertThrows(IllegalStateException::class.java) { runBlocking { session.finish() } }
            assertEquals("delivery failed", failure.message)
            assertEquals("Voix inconnue: words", store.file.readText())
            assertTrue(session.hasSpeakerLabels)
        } finally { session.close(); store.dispose() }
    }

    @Test fun speakerMetadataRequiresCommittedOutputAndSurvivesClose(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), onSegment = {},
            decode = { error("Unexpected plain decode") }, decodeSpeakers = { emptyList() },
            finishSpeakers = { listOf(SpeechSpan("words", 0)) })
        try {
            assertFalse(session.hasSpeakerLabels)
            session.finish()
            assertEquals("Speaker 1: words", store.file.readText())
            assertTrue(session.hasSpeakerLabels)
            session.close()
            assertTrue(session.hasSpeakerLabels)
        } finally { session.close(); store.dispose() }
    }

    @Test fun enabledSpeakerDecoderWithNoSpeechDoesNotClaimLabels(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), onSegment = {},
            decode = { "" }, decodeSpeakers = { emptyList() }, finishSpeakers = { emptyList() })
        try {
            session.finish()
            assertFalse(session.hasSpeakerLabels)
        } finally { session.close(); store.dispose() }
    }

    @Test fun plainOutputDoesNotClaimLabelsFromLabelLikeSpokenWords(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        val corrections = StreamingCorrections(listOf(DictionaryManager.ReplacementRule("words", "words")))
        val prefix = corrections.accept("Speaker 1: words")
        store.append(prefix)
        val session = TranscriptionSession(store, corrections, onSegment = {}, decode = { "" })
        try {
            session.finish()
            assertTrue(store.segments > 0)
            assertFalse(session.hasSpeakerLabels)
        } finally { session.close(); store.dispose() }
    }

    @Test fun speakerFinisherArrivesBeforePendingDictionaryTailIsFlushedAndSessionCloses(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        val corrections = StreamingCorrections(listOf(DictionaryManager.ReplacementRule("New York", "NYC")))
        // This prefix is already decoded; native lookahead still owns "York".
        assertTrue(corrections.acceptSpans(listOf(SpeechSpan("New", 0))).isEmpty())
        var finishes = 0
        var closes = 0
        val published = mutableListOf<String>()
        val session = TranscriptionSession(store, corrections, onSegment = { published += it },
            decode = { error("Finalization must not decode audio") }, onClosed = { closes++ },
            decodeSpeakers = { error("No buffered ASR audio") }, finishSpeakers = {
                assertEquals(0, closes)
                assertTrue(published.isEmpty())
                finishes++
                listOf(SpeechSpan(" York", 0))
            })
        try {
            session.finish()
            session.finish()
            assertEquals(listOf("Speaker 1: NYC"), published)
            assertEquals("Speaker 1: NYC", store.file.readText())
            assertEquals(1, finishes)
            assertEquals(1, closes)
        } finally { session.close(); store.dispose() }
    }

    @Test fun closeWhileSpeakerFinisherAwaitsDiscardsLateTextAndKeepsCorrectionTail(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        val corrections = StreamingCorrections(listOf(DictionaryManager.ReplacementRule("New York", "NYC")))
        corrections.acceptSpans(listOf(SpeechSpan("New", 0)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closes = 0
        val session = TranscriptionSession(store, corrections, onSegment = { fail("Closed finisher published text") },
            decode = { error("Unexpected ASR") }, onClosed = { closes++ }, decodeSpeakers = { emptyList() },
            finishSpeakers = { entered.complete(Unit); release.await(); listOf(SpeechSpan(" York", 0)) })
        try {
            val finishing = async(start = CoroutineStart.UNDISPATCHED) { session.finish() }
            assertTrue("Speaker finisher was never invoked", entered.isCompleted)
            session.close()
            release.complete(Unit)
            finishing.await()
            assertEquals(0, store.segments)
            assertFalse(session.hasSpeakerLabels)
            assertEquals("New", corrections.finish())
            assertEquals(1, closes)
        } finally { release.complete(Unit); session.close(); store.dispose() }
    }

    @Test fun cancellationRejectsANonCooperativeLateSpeakerFinisher(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        var closes = 0
        var late: Continuation<List<SpeechSpan>>? = null
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()),
            onSegment = { fail("Cancelled finisher published text") }, decode = { error("Unexpected ASR") },
            onClosed = { closes++ }, decodeSpeakers = { emptyList() }, finishSpeakers = { suspendCoroutine { late = it } })
        try {
            val finishing = launch(start = CoroutineStart.UNDISPATCHED) { session.finish() }
            assertNotNull("Speaker finisher was never invoked", late)
            finishing.cancel()
            checkNotNull(late).resume(listOf(SpeechSpan("late result", 0)))
            finishing.join()
            assertEquals(0, store.segments)
            assertEquals(1, closes)
        } finally { session.close(); store.dispose() }
    }

    @Test fun cancelledSessionDoesNotDecodeOrPublishItsBufferedTail(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        var closes = 0
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()),
            onSegment = { fail("Cancelled session published text") },
            decode = { error("Cancelled session decoded audio") }, onClosed = { closes++ })
        try {
            session.accept(ShortArray(16000))
            session.close()
            session.finish()
            session.close()
            assertEquals(1, closes)
            assertEquals(0, store.segments)
            assertThrows(IllegalStateException::class.java) { runBlocking { session.accept(shortArrayOf(1)) } }
        } finally { session.close(); store.dispose() }
    }

    @Test fun onSegmentClosingSessionKeepsCommittedPrefixWithoutFlushingCorrectionTail(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        val corrections = StreamingCorrections(listOf(DictionaryManager.ReplacementRule("err", "fixed")))
        val published = mutableListOf<String>()
        var closes = 0
        lateinit var session: TranscriptionSession
        session = TranscriptionSession(store, corrections,
            onSegment = { published += it; session.close() }, decode = { "one two three" },
            onClosed = { closes++ }, onProcessed = { _, _ -> fail("Processed callback after onSegment closed the session") })
        try {
            session.accept(ShortArray(16000))
            session.finish()
            session.finish()
            assertEquals(listOf("one two"), published)
            assertEquals("one two", store.file.readText())
            assertEquals("The final correction flush must not run after close", "three", corrections.finish())
            assertEquals(1, closes)
        } finally { session.close(); store.dispose() }
    }

    @Test fun closingDuringSuspendedSpeakerDecodeDiscardsItsLateResult(): Unit = runBlocking {
        closeDuringDecode(diarized = true)
    }

    @Test fun closingDuringSuspendedPlainDecodeDiscardsItsLateResult(): Unit = runBlocking {
        closeDuringDecode(diarized = false)
    }

    private suspend fun closeDuringDecode(diarized: Boolean): Unit = coroutineScope {
        val store = TranscriptStore(directory.newFile())
        store.append("already committed")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        var closes = 0
        suspend fun decode(): String { entered.complete(Unit); release.await(); return "late result" }
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()),
            onSegment = { published += it }, decode = { decode() }, onClosed = { closes++ },
            onProcessed = { _, _ -> fail("Processed callback after closing a suspended decode") },
            decodeSpeakers = if (diarized) { { listOf(SpeechSpan(decode(), 0)) } } else null)
        try {
            session.accept(ShortArray(16000))
            val finishing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.finish() } }
            entered.await()
            session.close()
            release.complete(Unit)
            val outcome = finishing.await()
            assertTrue("A closed session published its late decode", published.isEmpty())
            assertEquals("already committed", store.file.readText())
            outcome.getOrThrow()
            session.finish()
            session.close()
            assertEquals(1, closes)
        } finally { release.complete(Unit); session.close(); store.dispose() }
    }

    @Test fun cancellationRejectsEvenANonCooperativeLateSpeakerDecode(): Unit = runBlocking {
        val store = TranscriptStore(directory.newFile())
        val published = mutableListOf<String>()
        var closes = 0
        lateinit var late: Continuation<List<SpeechSpan>>
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()),
            onSegment = { published += it }, decode = { error("Unexpected plain decode") }, onClosed = { closes++ },
            onProcessed = { _, _ -> fail("Processed callback after coroutine cancellation") },
            decodeSpeakers = { suspendCoroutine { late = it } })
        try {
            session.accept(ShortArray(16000))
            val finishing = launch(start = CoroutineStart.UNDISPATCHED) { runCatching { session.finish() } }
            finishing.cancel()
            late.resume(listOf(SpeechSpan("late result", 0)))
            finishing.join()
            assertTrue("Cancellation must be checked after the decoder returns", published.isEmpty())
            assertEquals(0, store.segments)
            assertEquals(1, closes)
        } finally { session.close(); store.dispose() }
    }
}
