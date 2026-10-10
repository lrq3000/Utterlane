package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RecordingPipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val options = RuntimeOptions(queueSeconds = 1)

    @Test fun transcriptionGainUsesDerivedBuffersAndDrainsTailWhileRawSpoolIsPreserved() = runBlocking {
        val history = RecordingHistory(temporary.root)
        val config = io.github.lrq3000.utterlane.audio.MicrophoneOptions.HFP
        val recording = history.begin(HistoryRetention.FOREVER, microphone = config)
        val original = ShortArray(1617) { (it % 200 - 100).toShort() }
        val source = object : AudioCapture {
            override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
                for (offset in original.indices step 113) {
                    if (!shouldContinue()) return
                    onSamples(original.copyOfRange(offset, minOf(original.size, offset + 113)))
                }
            }
            override fun stop() {}
        }
        val gain = io.github.lrq3000.utterlane.audio.TranscriptionGain(config.gain)
        val derived = ArrayList<Short>()
        val sink: suspend (ShortArray) -> Unit = { derived.addAll(it.toList()) }
        val result = RecordingPipeline(source, history, recording, options).run(
            prepare = {}, accept = { gain.deliver(it, sink) }, finish = { gain.drain(sink) })
        recording.finish(false)
        assertNull(result.captureError); assertNull(result.storageError); assertNull(result.processingError)
        assertEquals(original.size, derived.size)
        val expectedGain = io.github.lrq3000.utterlane.audio.TranscriptionGain(config.gain)
        val expected = expectedGain.accept(original.copyOf()) + expectedGain.finish()
        assertArrayEquals(expected, derived.toShortArray())
        assertFalse(original.contentEquals(expected))
        assertArrayEquals(original, history.read(recording.entry.id, 0, original.size))
    }

    @Test fun consumerCleanupFailureDoesNotPreventAudioFinalization() = runBlocking {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        val result = RecordingPipeline(FiniteCapture(5), history, recording, options).run(
            prepare = {}, accept = {}, finish = {}, closeConsumer = { error("close failed") })
        assertEquals("close failed", result.processingError?.message)
        assertEquals(4000L, recording.writtenSamples)
        recording.finish(true)
        assertEquals(1, history.recoveryCount())
    }

    @Test fun recognitionOnlyCancellationIsAnOutcomeAndCaptureStillFinishes() = runBlocking {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        val source = FiniteCapture(5)
        try {
            val result = runCatching {
                RecordingPipeline(source, history, recording, options).run(
                    prepare = { throw CancellationException("recognition owner cancelled") }, accept = {}, finish = {})
            }
            assertTrue("Recognition-only cancellation escaped the pipeline", result.isSuccess)
            assertEquals("recognition owner cancelled", result.getOrThrow().processingError?.message)
            assertFalse(source.stopped.get())
            assertEquals(4000L, recording.writtenSamples)
        } finally { recording.finish(true) }
    }

    @Test fun fullWriterQueuePreservesTheOverflowTriggerInOrder() = runBlocking {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        val releaseWriter = CountDownLatch(1)
        val ended = CompletableDeferred<Unit>()
        val captured = AtomicInteger()
        val received = ArrayList<Short>()
        val source = FiniteCapture(100)
        val pipeline = RecordingPipeline(source, history, recording, options, save = {
            check(releaseWriter.await(5, TimeUnit.SECONDS))
            recording.append(it)
        })
        val operation = async { pipeline.run(prepare = {}, accept = { received.addAll(it.toList()) }, finish = {},
            onSamples = { captured.incrementAndGet() }, onCaptureEnded = { ended.complete(Unit) }) }
        try {
            withTimeout(5000) { ended.await() }
            assertTrue(source.stopped.get())
            assertTrue(captured.get() in 21..22) // Queue budget + writer-owned block + emergency slot.
            releaseWriter.countDown()
            val result = withTimeout(5000) { operation.await() }
            assertTrue(result.captureError is RecordingPipeline.CaptureCapacityException)
            assertEquals(captured.get() * 800L, recording.writtenSamples)
            assertEquals(captured.get() * 800, received.size)
            received.forEachIndexed { index, sample -> assertEquals((index / 800).toShort(), sample) }
        } finally { releaseWriter.countDown(); operation.cancelAndJoin(); recording.finish(true) }
    }

    @Test fun cancellationDrainsAlreadyCapturedAudioBeforeReturning() = runBlocking {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        val source = FiniteCapture(1000)
        val started = CompletableDeferred<Unit>()
        val captured = AtomicInteger()
        val operation = launch {
            RecordingPipeline(source, history, recording, options).run(prepare = { awaitCancellation() }, accept = {}, finish = {},
                onSamples = { if (captured.incrementAndGet() == 10) started.complete(Unit) })
        }
        withTimeout(5000) { started.await(); operation.cancelAndJoin() }
        assertTrue(source.stopped.get())
        assertEquals(captured.get() * 800L, recording.writtenSamples)
        recording.finish(true)
        history.prune(HistoryRetention.NONE)
        assertEquals(1, history.recoveryCount())
    }

    @Test fun writerFailureStopsCaptureButKeepsReadablePrefixAndReportsFailure() = runBlocking {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        val source = FiniteCapture(100)
        var writes = 0
        var read = 0
        val result = RecordingPipeline(source, history, recording, options, save = {
            if (++writes == 4) throw java.io.IOException("storage unavailable")
            recording.append(it)
        }).run(prepare = {}, accept = { read += it.size }, finish = {})
        assertEquals("storage unavailable", result.storageError?.message)
        assertTrue(source.stopped.get())
        assertEquals(2400, read)
        assertEquals(2400L, recording.writtenSamples)
        recording.finish(true)
    }

    @Test fun capturePersistsBeyondRamBudgetWhilePreparationWaitsAndDrainsAfterStop() = runBlocking {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        val ready = CompletableDeferred<Unit>()
        val captured = CompletableDeferred<Unit>()
        val source = FiniteCapture(100)
        val received = ArrayList<Short>()
        val pipeline = RecordingPipeline(source, history, recording, options)
        val operation = async {
            pipeline.run(prepare = { ready.await() }, accept = { received.addAll(it.toList()) }, finish = {},
                onCaptureEnded = { captured.complete(Unit) })
        }
        try {
            withTimeout(10000) { captured.await(); while (recording.writtenSamples < 80000) delay(10) }
            assertFalse(operation.isCompleted)
            assertEquals(80000L, recording.writtenSamples)
            ready.complete(Unit)
            assertNull(withTimeout(10000) { operation.await() }.processingError)
            assertEquals(80000, received.size)
            received.forEachIndexed { i, value -> assertEquals((i / 800).toShort(), value) }
        } finally { ready.complete(Unit); operation.cancelAndJoin(); recording.finish(false) }
    }

    @Test fun inferenceFailureDoesNotStopCaptureOrDropTheSavedTail() = runBlocking {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        val source = FiniteCapture(40)
        val outcome = RecordingPipeline(source, history, recording, options).run(
            prepare = {}, accept = { error("decoder failed") }, finish = { fail("Must not finalize failed recognition") })
        assertEquals("decoder failed", outcome.processingError?.message)
        assertFalse(source.stopped.get())
        assertEquals(32000L, recording.writtenSamples)
        recording.finish(true)
        history.prune(HistoryRetention.NONE)
        assertEquals(1, history.list().size)
    }

    @Test fun preparationFailureStillRecordsUntilSourceEnds() = runBlocking {
        val history = RecordingHistory(temporary.root)
        val recording = history.begin(HistoryRetention.NONE)
        val source = FiniteCapture(5)
        val outcome = RecordingPipeline(source, history, recording, options).run(
            prepare = { error("load failed") }, accept = { fail("Not prepared") }, finish = {})
        assertEquals("load failed", outcome.processingError?.message)
        assertFalse(source.stopped.get())
        assertEquals(4000L, recording.writtenSamples)
        recording.finish(true)
    }

    private class FiniteCapture(private val blocks: Int) : AudioCapture {
        val stopped = AtomicBoolean(false)
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            repeat(blocks) { index ->
                if (stopped.get() || !shouldContinue()) return
                onSamples(ShortArray(800) { index.toShort() })
                Thread.sleep(2)
            }
        }
        override fun stop() { stopped.set(true) }
    }
}
