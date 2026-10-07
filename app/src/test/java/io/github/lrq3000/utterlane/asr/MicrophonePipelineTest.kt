package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.history.MicrophoneRecordings
import io.github.lrq3000.utterlane.history.RecordingHistory
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MicrophonePipelineTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun capturesWhilePreparationWaitsAndReplaysEverySampleAfterStop(): Unit = runBlocking {
        val capture = Capture()
        val audio = recordings().begin(HistoryRetention.NONE)
        val ready = CompletableDeferred<MicrophonePipeline.Consumer>()
        val consumer = Consumer()
        val pipeline = MicrophonePipeline(capture, audio, RuntimeOptions(), prepare = { ready.await() })
        val run = async(Dispatchers.IO) { pipeline.run() }
        try {
            assertTrue(capture.started.await(3, TimeUnit.SECONDS))
            capture.frames.add(shortArrayOf(1, 2, 3))
            await { audio.samples == 3L }
            assertFalse(run.isCompleted)
            pipeline.stop()
            ready.complete(consumer)
            assertNull(withTimeout(3000) { run.await() }.failure)
            assertEquals(listOf<Short>(1, 2, 3), consumer.samples)
            assertTrue(consumer.finished)
            assertTrue(consumer.closed)
        } finally { run.cancelAndJoin(); audio.finish(true); audio.close() }
    }

    @Test fun preparationFailureKeepsRecordingUntilExplicitStop(): Unit = runBlocking {
        survivesFailure(failPreparation = true)
    }

    @Test fun inferenceFailureKeepsRecordingAndPreservesItsLaterAudio(): Unit = runBlocking {
        survivesFailure(failPreparation = false)
    }

    private suspend fun survivesFailure(failPreparation: Boolean): Unit = coroutineScope {
        val capture = Capture()
        val audio = recordings().begin(HistoryRetention.NONE)
        val failed = CompletableDeferred<MicrophonePipeline.Failure>()
        val pipeline = MicrophonePipeline(capture, audio, RuntimeOptions(), prepare = {
            if (failPreparation) error("load failed")
            object : Consumer() {
                override suspend fun accept(samples: ShortArray) { error("inference failed") }
            }
        }, onRecognitionFailure = { failed.complete(it) })
        val run = async(Dispatchers.IO) { pipeline.run() }
        try {
            assertTrue(capture.started.await(3, TimeUnit.SECONDS))
            capture.frames.add(shortArrayOf(10, 11))
            withTimeout(3000) { failed.await() }
            assertFalse(capture.stopped.get())
            assertFalse(run.isCompleted)
            capture.frames.add(shortArrayOf(12, 13))
            await { audio.samples == 4L }
            pipeline.stop()
            val outcome = withTimeout(3000) { run.await() }
            assertEquals(if (failPreparation) MicrophonePipeline.Stage.PREPARATION else MicrophonePipeline.Stage.INFERENCE,
                outcome.failure?.stage)
            assertArrayEquals(shortArrayOf(10, 11, 12, 13), audio.read(0, 4))
        } finally { run.cancelAndJoin(); audio.finish(true); audio.close() }
    }

    @Test fun stopBeforeDispatchNeverStartsTheMicrophone(): Unit = runBlocking {
        val capture = Capture()
        val audio = recordings().begin(HistoryRetention.NONE)
        val pipeline = MicrophonePipeline(capture, audio, RuntimeOptions(), prepare = { Consumer() })
        pipeline.stop()
        try {
            withTimeout(3000) { pipeline.run() }
            assertEquals(1L, capture.started.count)
            assertEquals(0L, audio.samples)
        } finally { audio.finish(false); audio.close() }
    }

    @Test fun cancellationStopsCaptureAndCancelsBlockedPreparation(): Unit = runBlocking {
        val capture = Capture()
        val audio = recordings().begin(HistoryRetention.NONE)
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val pipeline = MicrophonePipeline(capture, audio, RuntimeOptions(), prepare = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { released.complete(Unit) }
        })
        val run = launch(Dispatchers.IO) { pipeline.run() }
        try {
            withTimeout(3000) { entered.await() }
            assertTrue(capture.started.await(3, TimeUnit.SECONDS))
            withTimeout(3000) { run.cancelAndJoin(); released.await() }
            assertTrue(capture.stopped.get())
        } finally { run.cancelAndJoin(); audio.finish(true); audio.close() }
    }

    @Test fun writeFailureStopsCaptureAndKeepsThePublishedPrefix(): Unit = runBlocking {
        val capture = Capture()
        val audio = recordings().begin(HistoryRetention.NONE)
        val broken = object : MicrophonePipeline.Audio by audio {
            override fun append(samples: ShortArray) {
                if (audio.samples > 0) error("disk full")
                audio.append(samples)
            }
        }
        val pipeline = MicrophonePipeline(capture, broken, RuntimeOptions(), prepare = { Consumer() })
        val run = async(Dispatchers.IO) { pipeline.run() }
        try {
            capture.frames.add(shortArrayOf(21, 22))
            await { audio.samples == 2L }
            capture.frames.add(shortArrayOf(23))
            val result = withTimeout(3000) { run.await() }
            assertEquals(MicrophonePipeline.Stage.STORAGE, result.failure?.stage)
            assertTrue(capture.stopped.get())
            assertArrayEquals(shortArrayOf(21, 22), audio.read(0, 2))
        } finally { run.cancelAndJoin(); audio.finish(true); audio.close() }
    }

    @Test fun fullWriterQueueStillSavesTheBlockThatTriggersStop(): Unit = runBlocking {
        val capture = Capture()
        val audio = recordings().begin(HistoryRetention.NONE)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : MicrophonePipeline.Audio by audio {
            override fun append(samples: ShortArray) {
                if (audio.samples == 0L) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                audio.append(samples)
            }
        }
        val pipeline = MicrophonePipeline(capture, slow, RuntimeOptions(queueSeconds = 1), prepare = { Consumer() })
        val run = async(Dispatchers.IO) { pipeline.run() }
        try {
            capture.frames.add(ShortArray(3200) { 1 })
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repeat(6) { index -> capture.frames.add(ShortArray(3200) { (index + 2).toShort() }) }
            await { capture.stopped.get() }
            release.countDown()
            assertEquals(MicrophonePipeline.Stage.CAPACITY, withTimeout(3000) { run.await() }.failure?.stage)
            assertEquals(22400L, audio.samples)
            repeat(7) { index -> assertArrayEquals(ShortArray(3200) { (index + 1).toShort() }, audio.read(index * 3200L, 3200)) }
        } finally { release.countDown(); run.cancelAndJoin(); audio.finish(true); audio.close() }
    }

    private fun recordings() = MicrophoneRecordings(
        RecordingHistory(File(temporary.root, "history")), File(temporary.root, "temporary"))

    private suspend fun await(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) delay(5) }

    private open class Consumer : MicrophonePipeline.Consumer {
        val samples = mutableListOf<Short>()
        var finished = false
        var closed = false
        override suspend fun accept(samples: ShortArray) { this.samples.addAll(samples.toList()) }
        override suspend fun finish() { finished = true }
        override fun close() { closed = true }
    }

    private class Capture : AudioCapture {
        val started = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val frames = LinkedBlockingQueue<ShortArray>()
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            if (stopped.get()) return
            started.countDown()
            while (!stopped.get() && shouldContinue()) frames.poll(10, TimeUnit.MILLISECONDS)?.let(onSamples)
        }
        override fun stop() { stopped.set(true) }
    }
}
