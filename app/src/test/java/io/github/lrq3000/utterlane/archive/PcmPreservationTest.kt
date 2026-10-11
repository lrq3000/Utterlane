package io.github.lrq3000.utterlane.archive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PcmPreservationTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun workerCheckpointCannotEraseAConcurrentParentStop() = interleave(checkpointFirst = true)
    @Test fun parentStopCannotRegressAConcurrentWorkerCheckpoint() = interleave(checkpointFirst = false)
    @Test fun legacyStopAndCursorSurviveNewWritesAndRepeatedCancellation() {
        val directory = temp.newFolder()
        AudioMetadata.write(java.io.File(directory, "preservation.properties"), java.util.Properties().apply {
            setProperty("stopped", "true"); setProperty("reclaimed", "16000"); setProperty("warning", "legacy stop")
        })
        val worker = PcmPreservation(directory)
        worker.advance(32000)
        val parent = PcmPreservation(directory)
        parent.preserve("first fence"); parent.preserve("later callback")
        val state = PcmPreservation(directory).state()
        assertTrue(state.stopped); assertEquals(32000L, state.reclaimed); assertEquals("first fence", state.warning)
        assertThrows(IllegalArgumentException::class.java) { worker.advance(16000) }
    }
    private fun interleave(checkpointFirst: Boolean) {
        val directory = temp.newFolder()
        val pendingWrite = CountDownLatch(1); val allowWrite = CountDownLatch(1)
        // Independent writer instances deliberately share no JVM monitor, exactly
        // as the real parent and worker processes. Both still perform actual IO.
        val delayed = PcmPreservation(directory) { file, p ->
            pendingWrite.countDown(); check(allowWrite.await(5, TimeUnit.SECONDS)); AudioMetadata.write(file, p)
        }
        val other = PcmPreservation(directory)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit { if (checkpointFirst) delayed.advance(16000) else delayed.preserve("User stop") }
            assertTrue(pendingWrite.await(5, TimeUnit.SECONDS))
            if (checkpointFirst) other.preserve("User stop") else other.advance(16000)
            allowWrite.countDown(); task.get(5, TimeUnit.SECONDS)
            val recovered = PcmPreservation(directory).state()
            assertTrue("stop survives the other process's stale write", recovered.stopped)
            assertEquals("cursor survives the other process's stale write", 16000L, recovered.reclaimed)
            assertEquals("User stop", recovered.warning)
        } finally { allowWrite.countDown(); executor.shutdownNow() }
    }
}
