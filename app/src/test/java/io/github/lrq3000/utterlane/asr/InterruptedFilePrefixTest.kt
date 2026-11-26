package io.github.lrq3000.utterlane.asr

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InterruptedFilePrefixTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun nativeWindowCanBeAbandonedWithoutDroppingPreviouslyDecodedCorrectionTail() = runBlocking {
        val file = File(temp.root, "prefix.txt")
        val store = TranscriptStore(file)
        val calls = RecognitionCallQueue()
        val secondStarted = CompletableDeferred<Unit>()
        val finishNative = CompletableDeferred<Unit>()
        val source = ShortArray(22 * 16000) { (1000 + it / 16000).toShort() }
        val processed = mutableListOf<Long>()
        val corrections = listOf(DictionaryManager.ReplacementRule("yellow rabbit", "white rabbit"))
        val session = TranscriptionSession(store, StreamingCorrections(corrections), {},
            decode = { window -> calls.withTurn {
                awaitNativeReply {
                    if (window.ownedStart == 0L) "first yellow rabbit" else {
                        secondStarted.complete(Unit)
                        finishNative.await()
                        "do not publish this reply"
                    }
                }
            } }, onProcessed = { end, _ -> processed += end }, checkpointFileWindows = true)
        try {
            val input = launch { session.accept(source) }
            withTimeout(5000) { secondStarted.await(); input.cancelAndJoin() }
            val offset = session.sealInterruptedFilePrefix()
            assertEquals(160_000L, offset)
            assertEquals(processed.single(), offset)
            assertEquals("first white rabbit", file.readText())
            finishNative.complete(Unit)
            withTimeout(5000) { calls.withTurn {} }
            assertEquals("first white rabbit", file.readText())
            val replayed = mutableListOf<Short>()
            val next = TranscriptionSession(store, StreamingCorrections(emptyList()), {}, decode = { window ->
                val first = (window.ownedStart - window.startSample).toInt()
                val end = (window.ownedEnd - window.startSample).toInt()
                replayed.addAll(window.samples.copyOfRange(first, end).asList())
                if (window.ownedStart == 0L) "replayed suffix" else ""
            }, checkpointFileWindows = true)
            next.accept(source.copyOfRange(offset.toInt(), source.size)); next.finish()
            assertArrayEquals(source.copyOfRange(160_000, source.size), replayed.toShortArray())
            assertEquals("first white rabbit replayed suffix", file.readText())
        } finally { finishNative.complete(Unit); session.close(); store.dispose() }
    }

    @Test fun fixedSpeakerInterruptionKeepsPendingLocalWordsWithoutWaitingForNativeFinish() = runBlocking {
        val file = File(temp.root, "fixed-speaker.txt")
        val store = TranscriptStore(file)
        val eofWindow = CompletableDeferred<Unit>()
        val nativeFinish = CompletableDeferred<Unit>()
        val session = TranscriptionSession(store,
            StreamingCorrections(listOf(DictionaryManager.ReplacementRule("yellow rabbit", "white rabbit"))), {},
            decode = { error("Expected labeled recognition") }, decodeSpeakers = { window ->
                if (window.ownedStart == 0L) listOf(SpeechSpan("first yellow rabbit", 0))
                else { eofWindow.complete(Unit); nativeFinish.await(); listOf(SpeechSpan("late", 0)) }
            }, finishSpeakers = { error("Local flush must not enter native finalization") },
            checkpointFileWindows = true, speakerNativeLookahead = false)
        try {
            val input = launch { session.accept(ShortArray(12 * 16000) { 1000 }); session.finish() }
            withTimeout(5000) { eofWindow.await(); input.cancelAndJoin() }
            assertEquals(160_000L, session.sealInterruptedFilePrefix())
            assertEquals("Speaker 1: first white rabbit", file.readText())
            assertTrue(session.hasSpeakerLabels)
        } finally { nativeFinish.complete(Unit); session.close(); store.dispose() }
    }

    @Test fun cancellationWhileFormattingTheEofTailCannotLoseThoseWords() = runBlocking {
        val file = File(temp.root, "eof-tail.txt")
        val store = TranscriptStore(file)
        lateinit var producer: kotlinx.coroutines.Job
        val session = TranscriptionSession(store,
            StreamingCorrections(listOf(DictionaryManager.ReplacementRule("yellow rabbit", "white rabbit"))), {},
            decode = { error("Expected labeled recognition") },
            decodeSpeakers = { listOf(SpeechSpan("yellow rabbit", 0)) },
            speakerLabel = { producer.cancel(); "Speaker 1" }, finishSpeakers = { emptyList() },
            checkpointFileWindows = true, speakerNativeLookahead = false)
        try {
            producer = launch(start = CoroutineStart.LAZY) { session.accept(ShortArray(16000) { 1000 }); session.finish() }
            withTimeout(5000) { producer.join() }
            assertTrue(producer.isCancelled)
            assertEquals(16_000L, session.sealInterruptedFilePrefix())
            assertEquals("Speaker 1: white rabbit", file.readText())
        } finally { session.close(); store.dispose() }
    }

    @Test fun offsetAndOutputAreCommittedBeforeSuspendingPresentation() = runBlocking {
        val file = File(temp.root, "presentation.txt")
        val store = TranscriptStore(file)
        val presenting = CompletableDeferred<Unit>()
        val session = TranscriptionSession(store, StreamingCorrections(emptyList()), {
            presenting.complete(Unit)
            CompletableDeferred<Unit>().await()
        }, decode = { "committed window" }, checkpointFileWindows = true)
        try {
            val input = launch { session.accept(ShortArray(16 * 16000) { 1000 }) }
            withTimeout(5000) { presenting.await(); input.cancelAndJoin() }
            assertTrue(session.sealInterruptedFilePrefix() > 0L)
            assertEquals("committed window", file.readText())
        } finally { session.close(); store.dispose() }
    }
}
