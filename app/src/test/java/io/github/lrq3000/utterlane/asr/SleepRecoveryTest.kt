package io.github.lrq3000.utterlane.asr

import android.media.AudioRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SleepRecoveryTest {
    @Test fun routeRecoveryDrainsExistingAudioAndStopWinsBeforeReopen() {
        var requests = 0
        var reopened = 0
        val samples = mutableListOf<Short>()
        var reads = 0
        CaptureReadLoop(clock = { 0 }, waitForFrames = {}).run(
            read = { if (++reads == 1) { it[0] = 42; 1 } else 0 },
            reopen = { reopened++ }, onSamples = { samples.add(it.single()) },
            shouldContinue = { requests == 0 && reads < 4 }, routeRecoveryRequested = { requests++; true })
        assertEquals(listOf(42.toShort()), samples)
        assertEquals(1, requests)
        assertEquals("A recovery request may race with Stop", 0, reopened)
    }

    @Test fun routeRecoveryReopensOnceOnTheCaptureWorkerAndContinuesTheSameStream() {
        var reopened = 0
        var reads = 0
        val samples = mutableListOf<Short>()
        CaptureReadLoop(clock = { 0 }, waitForFrames = {}).run(
            read = { reads++; if (reopened == 0) 0 else { it[0] = 99; 1 } },
            reopen = { reopened++ }, onSamples = { samples.add(it.single()) },
            shouldContinue = { samples.isEmpty() && reads < 4 }, routeRecoveryRequested = { reopened == 0 })
        assertEquals(1, reopened)
        assertEquals(listOf(99.toShort()), samples)
    }
    @Test fun stopWithoutIncomingFramesDoesNotNeedToUnblockANativeRead() {
        var running = true
        var reads = 0
        val loop = CaptureReadLoop(clock = { 0 }, waitForFrames = { running = false })
        loop.run({ reads++; 0 }, { fail("Must not reopen on Stop") }, { fail("No frames") }, { running })
        assertEquals(1, reads)
    }

    @Test fun wakeDrainsExistingFramesBeforeReopeningStalledCapture() {
        var time = 0L
        var reopened = 0
        val samples = mutableListOf<Short>()
        val loop = CaptureReadLoop(clock = { time }, waitForFrames = { time += 100 })
        loop.resumeAfterSleep()
        loop.run({ buffer ->
            when {
                samples.isEmpty() -> { buffer[0] = 42; 1 }
                reopened == 0 -> 0
                else -> { buffer[0] = 43; 1 }
            }
        }, { reopened++ }, { samples.add(it.single()) }, { samples.size < 2 })
        assertEquals(1, reopened)
        assertEquals(listOf(42.toShort(), 43.toShort()), samples)
    }

    @Test fun healthyCaptureAfterWakeIsNotReopened() {
        var time = 0L
        val loop = CaptureReadLoop(clock = { time }, waitForFrames = {})
        loop.resumeAfterSleep()
        loop.run({ it[0] = 1; time += 100; 1 }, { fail("Healthy capture must be retained") }, {}, { time < 3000 })
    }

    @Test fun secondSleepPausesRecoveryUntilTheNextWake() {
        var time = 0L
        var awake = true
        var reopened = 0
        val samples = mutableListOf<Short>()
        lateinit var loop: CaptureReadLoop
        loop = CaptureReadLoop(clock = { time }, waitForFrames = {
            time += 100
            if (time == 10_000L) { awake = true; loop.resumeAfterSleep() }
        })
        loop.resumeAfterSleep()
        loop.run({ buffer ->
            when {
                samples.isEmpty() -> { buffer[0] = 1; awake = false; 1 }
                reopened == 0 -> 0
                else -> { buffer[0] = 2; 1 }
            }
        }, {
            assertTrue("Must not reopen or fail while asleep", awake)
            assertTrue(time >= 11_500)
            reopened++
        }, { samples.add(it.single()) }, { samples.size < 2 }, { awake })
        assertEquals(listOf(1.toShort(), 2.toShort()), samples)
        assertEquals(1, reopened)
    }

    @Test fun permanentlyDeadRecorderFailsInsteadOfLeavingDrainInLimbo() {
        var reopened = 0
        val loop = CaptureReadLoop(clock = { 0 }, waitForFrames = {})
        try {
            loop.run({ AudioRecord.ERROR_DEAD_OBJECT }, { reopened++ }, {}, { true })
            fail("Permanent capture failure must close the queue")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("read error")) }
        assertEquals(1, reopened)
    }

    @Test fun wakeRecoveryWithoutFramesHasFiniteDeadline() {
        var time = 0L
        var reopened = 0
        val loop = CaptureReadLoop(clock = { time }, waitForFrames = { time += 100 })
        loop.resumeAfterSleep()
        try {
            loop.run({ 0 }, { reopened++ }, {}, { true })
            fail("Recovery must report capture failure and allow queued audio to drain")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("resume")) }
        assertEquals(1, reopened)
        assertTrue(time <= 6600)
    }

    @Test fun sleepDoesNotExhaustWorkerBudgetButAwakeHangStillTimesOut() {
        val deadline = AwakeDeadline(1000, 0, false)
        assertFalse(deadline.expired(500, false))
        assertFalse(deadline.expired(600, true))
        assertFalse(deadline.expired(600_000, true))
        assertFalse(deadline.expired(600_100, false))
        assertFalse(deadline.expired(600_599, false))
        assertTrue(deadline.expired(600_600, false))
    }

    @Test fun sleepingDeliveryResumesFromByteCursorWithoutLosingUnicodeOrRepeatingText() = runBlocking {
        withStore { store ->
            val cursor = TranscriptCursor()
            val result = StringBuilder()
            store.append("before sleep 🙂")
            cursor.drain(store, { true }) { result.append(it); true }
            store.append("é中".repeat(5000))
            cursor.drain(store, { false }) { fail("Locked editor must not receive text"); false }
            assertTrue(cursor.hasPending(store))
            assertEquals("before sleep 🙂", result.toString())
            cursor.drain(store, { true }) { assertTrue(it.toByteArray().size <= TranscriptStore.PREVIEW_LIMIT); result.append(it); true }
            cursor.drain(store, { true }) { fail("Already delivered"); false }
            assertEquals(store.file.readText(), result.toString())
            assertFalse(result.contains('\uFFFD'))
            assertFalse(cursor.hasPending(store))
        }
    }

    @Test fun editorLossDuringDiskReadDoesNotAdvanceDeliveryOrDiscardRecovery() = runBlocking {
        withStore { store ->
            store.append("keep this tail")
            val cursor = TranscriptCursor()
            var checks = 0
            cursor.drain(store, { ++checks == 1 }) { fail("Editor changed during IO"); false }
            assertEquals(0L, cursor.offset)
            assertTrue(cursor.hasPending(store))
            cursor.drain(store, { true }) { false }
            assertTrue(cursor.failed)
            cursor.drain(store, { true }) { fail("Do not retry ambiguous insertion into another editor"); false }
            assertEquals(0L, cursor.offset)
            assertEquals("keep this tail", store.file.readText())
        }
    }

    private suspend fun withStore(test: suspend (TranscriptStore) -> Unit) {
        val directory = Files.createTempDirectory("sleep-delivery").toFile()
        val store = TranscriptStore(File(directory, "transcript.txt"))
        try { test(store) } finally { store.dispose(); directory.deleteRecursively() }
    }
}
