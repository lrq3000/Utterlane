package io.github.lrq3000.utterlane.diagnostics

import io.github.lrq3000.utterlane.asr.RecognitionActivity
import io.github.lrq3000.utterlane.asr.RecognitionActivityOwnership
import io.github.lrq3000.utterlane.asr.RecognitionRuntimeConfiguration
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecognitionDiagnosticOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun overlappingOptOutDoesNotInheritEarlierSessionsConsent(): Unit = runBlocking {
        overlappingSessions(firstEnabled = true)
    }

    @Test fun overlappingOptInDoesNotInheritEarlierSessionsSuppression(): Unit = runBlocking {
        overlappingSessions(firstEnabled = false)
    }

    private suspend fun overlappingSessions(firstEnabled: Boolean) {
        val worker = RuntimeOptions(diagnostics = firstEnabled, asrThreads = 4, inferenceStallSeconds = 300)
        val second = worker.copy(diagnostics = !firstEnabled, asrThreads = 2, inferenceStallSeconds = 10,
            diarizationThreads = 2)
        val configuration = RecognitionRuntimeConfiguration().apply { applied(worker) }
        val ownership = RecognitionActivityOwnership()
        val log = BoundedDiagnosticLog(DiagnosticRingFiles(File(temporary.root, "logs")), { DiagnosticEnvironment() })
        val diagnostics = LocalRecognitionDiagnostics(log)
        try {
            // Session A remains alive while requests from A and B interleave. Only the
            // request scope changes: KEEP must not reconfigure the worker for B.
            val a = ownership.begin(worker, worker, sessionId = 1)
            publish(ownership, diagnostics, 1)
            ownership.finish(a)
            assertEquals(RecognitionRuntimeConfiguration.Change.KEEP, configuration.change(second, hasSessions = true))
            val b = ownership.begin(second, worker, sessionId = 2)
            publish(ownership, diagnostics, 2)
            ownership.finish(b)
            val resumedA = ownership.begin(worker, worker, sessionId = 1)
            publish(ownership, diagnostics, 3)
            ownership.finish(resumedA)

            val lines = contents(log.snapshot()).lines().filter { it.isNotBlank() }
            val ids = lines.map { Regex("\"request_id\":(\\d+)").find(it)!!.groupValues[1].toInt() }
            assertEquals(if (firstEnabled) listOf(1, 3) else listOf(2), ids)
            if (!firstEnabled) {
                val line = lines.single()
                // The stored shared config still has A's consent/threads/budgets; B's
                // consent and session settings have their own explicit provenance.
                val shared = objectField(line, "options")
                val initiating = objectField(line, "operation_options")
                assertTrue(shared.contains("\"inference_stall_seconds\":\"300\""))
                assertTrue(shared.contains("\"asr_threads\":\"4\""))
                assertTrue(shared.contains("\"diagnostics\":\"false\""))
                assertTrue(initiating.contains("\"inference_stall_seconds\":\"10\""))
                assertTrue(initiating.contains("\"asr_threads\":\"2\""))
                assertTrue(initiating.contains("\"diagnostics\":\"true\""))
                assertTrue(line.contains("\"effective_asr_threads\":4"))
                assertTrue(line.contains("\"effective_diarization_threads\":2"))
            }
            assertEquals(RecognitionRuntimeConfiguration.Change.RELOAD, configuration.change(second, hasSessions = false))
        } finally { log.close() }
    }

    @Test fun closingAnotherSessionCannotClearCurrentConsentAndLateFinishCannotClearReplacement() {
        val ownership = RecognitionActivityOwnership()
        val enabled = RuntimeOptions(diagnostics = true)
        val disabled = enabled.copy(diagnostics = false)
        val first = ownership.begin(enabled, enabled, sessionId = 1)
        ownership.closeSession(2)
        assertSame(first, ownership.snapshot())
        ownership.closeSession(1)
        assertNull(ownership.snapshot())
        val second = ownership.begin(disabled, enabled, sessionId = 2)
        ownership.finish(first)
        assertSame(second, ownership.snapshot())
        ownership.finish(second)
        assertNull(ownership.snapshot())
    }

    @Test fun resetClearsPreparationOrInferenceAndLateCloseCannotRevokeNewOperation(): Unit = runBlocking {
        val ownership = RecognitionActivityOwnership()
        val enabled = RuntimeOptions(diagnostics = true)
        val log = BoundedDiagnosticLog(DiagnosticRingFiles(File(temporary.root, "logs")), { DiagnosticEnvironment() })
        val diagnostics = LocalRecognitionDiagnostics(log)
        try {
            val prepare = ownership.begin(enabled, enabled)
            ownership.reset()
            publish(ownership, diagnostics, 1)
            assertNull(ownership.snapshot())
            val inference = ownership.begin(enabled, enabled, sessionId = 1)
            ownership.finish(prepare)
            assertSame(inference, ownership.snapshot())
            ownership.reset()
            publish(ownership, diagnostics, 2)
            val replacement = ownership.begin(enabled, enabled, sessionId = 2)
            ownership.closeSession(1)
            ownership.finish(inference)
            assertSame(replacement, ownership.snapshot())
            publish(ownership, diagnostics, 3)
            ownership.closeSession(2)
            publish(ownership, diagnostics, 4)
            val text = contents(log.snapshot())
            assertEquals(1, text.lines().count { it.isNotBlank() })
            assertTrue(text.contains("\"request_id\":3"))
        } finally { log.close() }
    }

    private fun publish(ownership: RecognitionActivityOwnership, diagnostics: LocalRecognitionDiagnostics, requestId: Int) {
        ownership.snapshot()?.let {
            diagnostics.activity(RecognitionActivity(requestId = requestId, stage = "completed"), it.operationOptions, it.workerOptions)
        }
    }

    private fun contents(file: File): String = ZipFile(file).use { zip ->
        zip.entries().asSequence().joinToString("") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
    }

    private fun objectField(line: String, name: String): String = Regex("\"$name\":\\{([^}]*)}").find(line)!!.groupValues[1]
}
