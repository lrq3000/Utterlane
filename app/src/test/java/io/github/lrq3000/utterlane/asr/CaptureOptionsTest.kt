package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptureOptionsTest {
    @Test fun bufferPolicyPreservesDefaultsAndHonorsAndroidMinimum() {
        val policy = CaptureBufferPolicy(RuntimeOptions())
        assertEquals(800, policy.blockSamples)
        assertEquals(320000, policy.maximumSamples)
        assertEquals(1000, policy.queueCapacity)
        assertEquals(32000, policy.recorderBufferBytes(1024))
        assertEquals(64000, policy.recorderBufferBytes(64000))
        val small = CaptureBufferPolicy(RuntimeOptions(captureBufferSeconds = 0.05001, captureBlockMs = 1, queueSeconds = 1))
        assertEquals(16, small.blockSamples)
        assertEquals(1602, small.recorderBufferBytes(100))
        assertEquals(1000, small.queueCapacity)
        assertEquals(3200, CaptureBufferPolicy(RuntimeOptions(captureBlockMs = 200)).blockSamples)
    }

    @Test fun queueSnapshotCannotResizeOrLoseAcceptedAudio() = runBlocking<Unit> {
        var options = RuntimeOptions(queueSeconds = 2, captureBlockMs = 200)
        val queue = BoundedAudioQueue(options)
        val accepted = (0 until 10).map { index -> ShortArray(3200) { index.toShort() } }
        accepted.forEach { assertTrue(queue.offer(it)) }
        options = options.copy(queueSeconds = 1)
        assertFalse(queue.offer(shortArrayOf(99)))
        assertArrayEquals(accepted[0], queue.blocks.receive())
        assertTrue(queue.offer(accepted[0]))
        queue.close()
        val actual = mutableListOf<ShortArray>()
        for (block in queue.blocks) actual += block
        (accepted.drop(1) + listOf(accepted[0])).zip(actual).forEach { (expected, block) -> assertArrayEquals(expected, block) }
        assertEquals(10, actual.size)
        // Only a new queue receives the changed budget.
        val next = BoundedAudioQueue(options)
        repeat(5) { assertTrue(next.offer(ShortArray(3200))) }
        assertFalse(next.offer(shortArrayOf(1)))
        next.close()
    }

    @Test fun partialReadObjectBudgetIsBoundedAndRollsBackRejectedOffers() = runBlocking {
        val queue = BoundedAudioQueue(RuntimeOptions(queueSeconds = 1))
        repeat(50) { assertTrue(queue.offer(shortArrayOf(it.toShort()))) }
        assertFalse(queue.offer(shortArrayOf(99)))
        assertEquals(0.toShort(), queue.blocks.receive().single())
        assertTrue(queue.offer(shortArrayOf(50)))
        queue.close()
        assertFalse(queue.offer(shortArrayOf(51)))
        val values = mutableListOf<Short>()
        for (block in queue.blocks) values += block.single()
        assertEquals((1..50).map { it.toShort() }, values)
    }

    @Test fun recoveryDeadlinesUseTheStartingSnapshotAndDrainBeforeReopen() {
        var now = 0L
        var options = RuntimeOptions(captureBlockMs = 200, wakeRecoveryMs = 200, wakeReopenMs = 400)
        val loop = CaptureReadLoop(clock = { now }, waitForFrames = { now += 100 }, options = options)
        options = options.copy(wakeRecoveryMs = 10000)
        assertEquals(10000, options.wakeRecoveryMs)
        var reads = 0
        var delivered = 0
        val reopenTimes = mutableListOf<Long>()
        loop.resumeAfterSleep()
        val failure = assertThrows(IllegalStateException::class.java) {
            loop.run({ buffer ->
                assertEquals(3200, buffer.size)
                if (reads++ == 0) { buffer[0] = 7; 1 } else 0
            }, { reopenTimes += now }, { delivered += it.size }, { true })
        }
        assertEquals(1, delivered)
        assertEquals(listOf(200L), reopenTimes)
        assertEquals(600L, now)
        assertTrue(failure.message!!.contains("resume"))
    }

    @Test fun stopAtRecoveryBoundaryDoesNotReopen() {
        var now = 0L
        var running = true
        val loop = CaptureReadLoop(clock = { now }, waitForFrames = { now = 100; running = false },
            options = RuntimeOptions(wakeRecoveryMs = 100))
        loop.resumeAfterSleep()
        loop.run({ 0 }, { fail("Stopped capture must not reopen") }, {}, { running })
    }

    @Test fun optionAwareEntryPointPreservesExistingInjectedCaptureContract() {
        var stopped = false
        val fake: AudioCapture = object : AudioCapture {
            override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
                if (shouldContinue()) onSamples(shortArrayOf(42))
            }
            override fun stop() { stopped = true }
        }
        var samples = 0
        fake.startRecording(RuntimeOptions(captureBlockMs = 200), { samples += it.size }, { !stopped })
        fake.stop()
        fake.startRecording(RuntimeOptions(), { fail("Stopped fake must not deliver") }, { !stopped })
        assertEquals(1, samples)
    }
}
