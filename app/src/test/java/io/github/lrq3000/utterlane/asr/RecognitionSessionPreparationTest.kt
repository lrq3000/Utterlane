package io.github.lrq3000.utterlane.asr

import io.github.lrq3000.utterlane.diagnostics.BoundedDiagnosticLog
import io.github.lrq3000.utterlane.diagnostics.DiagnosticEnvironment
import io.github.lrq3000.utterlane.diagnostics.DiagnosticRingFiles
import io.github.lrq3000.utterlane.diagnostics.LocalRecognitionDiagnostics
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecognitionSessionPreparationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun originalCallCapturesOptOutBeforeWaitingForInference(): Unit = runBlocking {
        queuedSnapshot(supplied = false)
    }

    @Test fun explicitEntryPointSnapshotSkipsPreferenceReadAndSurvivesQueue(): Unit = runBlocking {
        queuedSnapshot(supplied = true)
    }

    private suspend fun queuedSnapshot(supplied: Boolean): Unit = coroutineScope {
        val entryOptions = RuntimeOptions(diagnostics = false, asrThreads = 2, inferenceStallSeconds = 10)
        val preferences = MutableStateFlow(entryOptions)
        var reads = 0
        val mutex = Mutex(locked = true)
        val preparation = RecognitionSessionPreparation(mutex) { reads++; preferences.first() }
        val directory = File(temporary.root, "queued")
        val log = BoundedDiagnosticLog(DiagnosticRingFiles(directory), { DiagnosticEnvironment() })
        val diagnostics = LocalRecognitionDiagnostics(log)
        // With an explicit snapshot, even an edit between entry-point capture and
        // createSession must not replace capture's already chosen consent/options.
        if (supplied) preferences.value = entryOptions.copy(diagnostics = true)
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            preparation.prepare(if (supplied) entryOptions else null) { snapshot ->
                // Feed the real sink: shared worker consent cannot override this
                // operation's opt-out, including model preparation activity.
                diagnostics.activity(RecognitionActivity(requestId = 1, stage = "model_load", active = true),
                    snapshot, RuntimeOptions(diagnostics = true))
                snapshot
            }
        }
        try {
            assertFalse(queued.isCompleted)
            preferences.value = entryOptions.copy(diagnostics = true, asrThreads = 8, inferenceStallSeconds = 900)
            mutex.unlock()
            val prepared = queued.await()
            assertSame(entryOptions, prepared)
            assertFalse(prepared.diagnostics)
            assertEquals(if (supplied) 0 else 1, reads)
        } finally {
            queued.cancelAndJoin()
            log.close()
        }
        assertFalse("An entry-point opt-out must not write diagnostics while queued", directory.exists())
    }

    @Test fun cancelledWaitDoesNotPrepareAndFailedPreparationReleasesMutex(): Unit = runBlocking {
        val options = RuntimeOptions(diagnostics = false)
        val mutex = Mutex(locked = true)
        val preparation = RecognitionSessionPreparation(mutex) { options }
        var prepared = false
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            preparation.prepare(null) { prepared = true }
        }
        queued.cancelAndJoin()
        assertFalse(prepared)
        assertTrue(mutex.isLocked) // Cancellation cannot unlock someone else's inference.
        mutex.unlock()
        try {
            preparation.prepare(options) { throw IllegalStateException("preparation failed") }
            fail("Expected preparation failure")
        } catch (_: IllegalStateException) { /* The caller's existing cleanup handles this. */ }
        assertFalse(mutex.isLocked)
        assertSame(options, preparation.prepare(options) { it })
    }

    // Compile-time API contract: these are deliberately not executed against Android.
    // Adding a defaulted options parameter ahead of callbacks would break positional
    // calls or make the original trailing-lambda/default call forms ambiguous.
    @Suppress("unused")
    private suspend fun callStyles(manager: RecognizerManager, options: RuntimeOptions) {
        val processed: (Long, Long) -> Unit = { _, _ -> }
        val segment: suspend (String) -> Unit = {}
        manager.createSession()
        manager.createSession { segment(it) }
        manager.createSession(processed)
        manager.createSession(processed, segment)
        manager.createSession(processed) { segment(it) }
        manager.createSession(onSegment = segment)
        manager.createSession(options)
        manager.createSession(options) { segment(it) }
        manager.createSession(options, processed, segment)
        manager.createSession(options = options, onProcessed = processed) { segment(it) }
    }
}
