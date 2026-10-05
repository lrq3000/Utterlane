package io.github.lrq3000.utterlane.diagnostics

import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal val enabledOptions = RuntimeOptions(diagnostics = true)

class LocalRecognitionDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun defaultDisabledNeverWritesOrCollectsDeviceMetadata(): Unit = runBlocking {
        val root = File(temporary.root, "disabled")
        var metadataCalls = 0
        val log = BoundedDiagnosticLog(DiagnosticRingFiles(root), { metadataCalls++; DiagnosticEnvironment() })
        val diagnostics = LocalRecognitionDiagnostics(log)
        diagnostics.activity(RecognitionActivity(active = true, stage = "asr"), RuntimeOptions())
        diagnostics.capture(RuntimeOptions()).record(CaptureSnapshot(capturedSamples = 16000))
        log.close()
        assertFalse(root.exists())
        assertEquals(0, metadataCalls)
    }

    @Test fun throttleDropsRepeatedErrorsAndNeverSerializesContent(): Unit = runBlocking {
        val root = File(temporary.root, "logs")
        val log = BoundedDiagnosticLog(DiagnosticRingFiles(root), { DiagnosticEnvironment(deviceModel = "test-device") })
        var clock = 0L
        val diagnostics = LocalRecognitionDiagnostics(log) { clock }
        val active = RecognitionActivity(requestId = 4, stage = "speaker/transformer", active = true, completedUnits = 5)
        diagnostics.activity(active, enabledOptions)
        repeat(100) { diagnostics.activity(active.copy(stage = "../secret/" + "x".repeat(10000), message = "private transcript"), enabledOptions) }
        clock = 1000
        diagnostics.activity(active.copy(elapsedMillis = 1000, sinceProgressMillis = 750), enabledOptions)
        clock = 2000
        diagnostics.activity(active.copy(stage = "../secret/" + "x".repeat(10000), message = "private transcript", elapsedMillis = 2000), enabledOptions)
        repeat(20) { diagnostics.activity(active.copy(stage = "error", active = false, message = "private transcript"), enabledOptions) }
        val capture = diagnostics.capture(enabledOptions)
        capture.record(CaptureSnapshot(modelName = "secret model path", error = "private transcript", capturedSamples = 32000, processedSamples = 8000))
        val lines = contents(log.snapshot()).lines().filter { it.isNotBlank() }
        assertEquals(5, lines.size)
        val text = lines.joinToString("\n")
        listOf("private", "secret", "waveform", "probabilities", "level", "audio_ms", "rtf").forEach { assertFalse(it, text.contains(it)) }
        assertTrue(text.contains("\"backlog_ms\":1500"))
        assertTrue(text.contains("\"asr_threads\":\"4\""))
        assertTrue(text.contains("\"device_model\":\"test-device\""))
        assertTrue(text.contains("\"native_version_source\":\"build_pins_not_runtime_probe\""))
        log.close()
    }

    @Test fun storageFailureIsReportedOnceAndCannotBreakRecognition(): Unit = runBlocking {
        var reports = 0
        val files = object : DiagnosticStorage {
            override fun append(bytes: ByteArray): Boolean = throw java.io.IOException("private path")
            override fun snapshot() = File(temporary.root, "unused")
            override fun clear() {}
            override fun prune() {}
        }
        val log = BoundedDiagnosticLog(files, { DiagnosticEnvironment() }, onFailure = { reports++ })
        repeat(5) { log.offer(DiagnosticRecord.Capture(enabledOptions, "capturing", 16, 0)) }
        log.close()
        assertEquals(1, reports)
        assertEquals(5, log.droppedRecords)
    }

    @Test fun invalidOptionAndPhaseNamesAreRejectedBeforePersistence(): Unit = runBlocking {
        val root = File(temporary.root, "invalid")
        val log = BoundedDiagnosticLog(DiagnosticRingFiles(root), { DiagnosticEnvironment() })
        assertFalse(log.offer(DiagnosticRecord.Capture(enabledOptions.copy(diarizationMode = "../" + "a".repeat(10000)), "capturing", 0, 0)))
        try {
            DiagnosticRecord.Capture(enabledOptions, "../private", 0, 0)
            fail("Arbitrary names must not enter the queue")
        } catch (_: IllegalArgumentException) { /* expected */ }
        log.close()
        assertFalse(root.exists())
    }

    @Test fun requestFailureFollowedByWorkerDisconnectIsOneError(): Unit = runBlocking {
        val log = BoundedDiagnosticLog(DiagnosticRingFiles(File(temporary.root, "logs")), { DiagnosticEnvironment() })
        val diagnostics = LocalRecognitionDiagnostics(log)
        diagnostics.activity(RecognitionActivity(requestId = 4, stage = "error", message = "failure"), enabledOptions)
        // prepare() failure is followed by close(): the worker's disconnect uses ID 0.
        diagnostics.activity(RecognitionActivity(stage = "idle", message = "Model forcibly unloaded"), enabledOptions)
        assertEquals(1, contents(log.snapshot()).lines().count { it.isNotBlank() })
        log.close()
    }

    @Test fun capturedOptionsStayImmutableAndClearResetsErrorSuppression(): Unit = runBlocking {
        val log = BoundedDiagnosticLog(DiagnosticRingFiles(File(temporary.root, "logs")), { DiagnosticEnvironment() })
        val diagnostics = LocalRecognitionDiagnostics(log)
        val capture = diagnostics.capture(enabledOptions.copy(asrThreads = 2))
        capture.record(CaptureSnapshot(capturedSamples = 16))
        val error = RecognitionActivity(stage = "error", message = "do not retain")
        diagnostics.activity(error, enabledOptions)
        assertTrue(contents(log.snapshot()).contains("\"asr_threads\":\"2\""))
        diagnostics.clear()
        diagnostics.activity(error, enabledOptions)
        assertEquals(1, contents(log.snapshot()).lines().count { it.isNotBlank() })
        log.close()
    }

    @Test fun slowDiskCannotBlockConcurrentProducersAndPendingQueueIsBounded(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = object : DiagnosticStorage {
            override fun append(bytes: ByteArray): Boolean { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return true }
            override fun snapshot() = File(temporary.root, "snapshot.zip")
            override fun clear() {}
            override fun prune() {}
        }
        val log = BoundedDiagnosticLog(files, { DiagnosticEnvironment() }, capacity = 4)
        val record = DiagnosticRecord.Capture(enabledOptions, "capturing", 1, 0)
        val producers = Executors.newFixedThreadPool(4)
        try {
            assertTrue(log.offer(record))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val futures = (1..4).map { producers.submit { repeat(1000) { log.offer(record) } } }
            // Completes while append is still blocked. This checks the actual handoff,
            // not timing a fake fast filesystem and assuming the UI remains responsive.
            futures.forEach { it.get(2, TimeUnit.SECONDS) }
            assertTrue(log.droppedRecords >= 3996)
            val snapshot = async { log.snapshot() }
            yield()
            assertFalse(snapshot.isCompleted)
            release.countDown()
            snapshot.await()
        } finally { release.countDown(); producers.shutdownNow(); log.close() }
    }

    private fun contents(file: File): String = ZipFile(file).use { zip ->
        zip.entries().asSequence().joinToString("") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
    }
}
