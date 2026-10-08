package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated .recordingfirst QA identity: intentionally needs no model or microphone. */
@RunWith(AndroidJUnit4::class)
class RecordingFirstAndroidTest {
    @Test fun actualAudioRecordContinuesWhileTheModelIsUnavailable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        assertFalse(app.modelManager.isModelReady())
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.RECORD_AUDIO")
        val activity = instrumentation.startActivitySync(io.github.lrq3000.utterlane.history.HistoryActivity.intent(app)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val previous = app.settingsRepository.audioHistoryEnabled.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val completed = CompletableDeferred<SessionFailure?>()
        val closed = CompletableDeferred<Unit>()
        val microphone = MicrophoneSession(app, scope, onText = { _, _ -> },
            onComplete = { _, failure -> completed.complete(failure) }, onSessionClosed = { closed.complete(Unit) })
        var id: String? = null
        try {
            app.settingsRepository.setAudioHistoryEnabled(false)
            microphone.start()
            withTimeout(5000) { microphone.metrics.state.first { it.capturedSamples >= 16000 } }
            assertFalse(completed.isCompleted)
            microphone.stop()
            val failure = withTimeout(5000) { completed.await() }
            withTimeout(5000) { closed.await() }
            id = failure?.recoveryId
            assertEquals(SessionFailure.Kind.MODEL, failure?.kind)
            assertEquals(microphone.metrics.state.value.capturedSamples, app.recordingHistory.get(checkNotNull(id)).samples)
        } finally {
            microphone.cancel(); scope.cancel()
            id?.let { app.recordingHistory.delete(it); io.github.lrq3000.utterlane.history.RecordingRecovery.dismissNotification(app, it) }
            instrumentation.runOnMainSync { activity.finish() }
            app.settingsRepository.setAudioHistoryEnabled(previous)
        }
    }

    @Test fun explicitCancelDeletesTemporaryAudioRatherThanOfferingRecovery() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        val previous = app.settingsRepository.audioHistoryEnabled.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val source = CountingCapture()
        val closed = CompletableDeferred<Unit>()
        val before = app.recordingHistory.list().map { it.id }.toSet()
        val session = MicrophoneSession(app, scope, onText = { _, _ -> }, onComplete = { _, _ -> },
            recorder = source, onSessionClosed = { closed.complete(Unit) })
        try {
            app.settingsRepository.setAudioHistoryEnabled(false)
            session.start()
            assertTrue(source.sent.await(5, TimeUnit.SECONDS))
            session.cancel()
            withTimeout(10000) { closed.await() }
            assertEquals(before, app.recordingHistory.list().map { it.id }.toSet())
        } finally {
            session.cancel(); scope.cancel(); app.settingsRepository.setAudioHistoryEnabled(previous)
        }
    }

    @Test fun preparationAndFailureRemainVisibleWhileRecordingWithStatisticsOff() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val previous = app.settingsRepository.showTranscriptionStreamStatistics.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val metrics = CaptureMetrics()
        lateinit var panel: io.github.lrq3000.utterlane.ui.RecordingPanel
        var panelCreated = false
        try {
            app.settingsRepository.setShowTranscriptionStreamStatistics(false)
            instrumentation.runOnMainSync {
                panel = io.github.lrq3000.utterlane.ui.RecordingPanel(app, {}, {})
                panelCreated = true
                panel.bind(scope, metrics.state)
            }
            metrics.preparing(true); metrics.started()
            suspend fun awaitText(text: String) = withTimeout(5000) {
                while (true) {
                    var found = false
                    instrumentation.runOnMainSync {
                        fun contains(view: android.view.View): Boolean =
                            (view is android.widget.TextView && view.visibility == android.view.View.VISIBLE && view.text.toString() == text) ||
                                (view is android.view.ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) })
                        found = contains(panel)
                        // The initial loading frame is legitimate before the
                        // StateFlow collector renders the requested capture state.
                        if (found) assertEquals(android.view.View.VISIBLE, panel.findViewById<android.view.View>(R.id.recording_done).visibility)
                    }
                    if (found) break
                    delay(10)
                }
            }
            awaitText(app.getString(R.string.recording_model_loading))
            metrics.recognitionFailed("test failure")
            awaitText(app.getString(R.string.recording_recognition_unavailable))
        } finally {
            if (panelCreated) instrumentation.runOnMainSync { panel.release() }
            scope.cancel()
            app.settingsRepository.setShowTranscriptionStreamStatistics(previous)
        }
    }

    @Test fun stopWhilePreparingPreservesEverySpeakerWindowAndWaitsForFinalLabels() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        val recording = app.recordingHistory.begin(HistoryRetention.NONE)
        val store = TranscriptStore(java.io.File.createTempFile("pipeline-", ".txt", app.cacheDir))
        val source = CountingCapture()
        val prepare = CompletableDeferred<Unit>()
        val finalLabels = CompletableDeferred<Unit>()
        val finishing = CompletableDeferred<Unit>()
        val options = RuntimeOptions(asrWindowSeconds = 0.1, asrMinSeconds = 0.1,
            asrLeftContextSeconds = 0.0, asrRightContextSeconds = 0.0)
        var windows = 0
        val transcript = TranscriptionSession(store, StreamingCorrections(emptyList()), onSegment = {},
            decode = { error("Enabled labeling must never fall back to plain recognition") },
            decodeSpeakers = {
                delay(50) // Deliberately slower than this fixture's producer.
                listOf(SpeechSpan("segment${windows++}", 0))
            }, options = options, finishSpeakers = {
                finishing.complete(Unit)
                finalLabels.await()
                listOf(SpeechSpan("lastword", 0))
            })
        val operation = async(Dispatchers.IO) {
            RecordingPipeline(source, app.recordingHistory, recording, options).run(
                prepare = { prepare.await() }, accept = transcript::accept, finish = transcript::finish)
        }
        try {
            assertTrue(source.sent.await(5, TimeUnit.SECONDS))
            source.stop() // Stop must not cancel preparation or the processing tail.
            withTimeout(5000) { while (recording.writtenSamples < 8000) delay(10) }
            assertEquals(0, windows)
            prepare.complete(Unit)
            withTimeout(5000) { finishing.await() }
            assertFalse(operation.isCompleted)
            assertEquals(5, windows)
            finalLabels.complete(Unit)
            assertNull(withTimeout(5000) { operation.await() }.processingError)
            val text = store.file.readText()
            repeat(5) { assertTrue(text.contains("segment$it")) }
            assertTrue(text.endsWith("lastword"))
            assertTrue(text.contains("Speaker 1:"))
        } finally {
            source.stop(); prepare.complete(Unit); finalLabels.complete(Unit)
            operation.cancelAndJoin(); transcript.close(); store.dispose(); recording.finish(false)
        }
    }

    @Test fun unavailableModelDoesNotPreventOrTerminateCapture() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        assertTrue("Use the isolated QA identity", app.packageName.endsWith(".recordingfirst"))
        assertFalse("This reproduction needs an uninstalled speech model", app.modelManager.isModelReady())
        val oldRetention = app.settingsRepository.historyRetention.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val capture = CountingCapture()
        val completed = CompletableDeferred<SessionFailure?>()
        val session = MicrophoneSession(app, scope, onText = { _, _ -> },
            onComplete = { _, failure -> completed.complete(failure) }, recorder = capture)
        try {
            app.settingsRepository.setHistoryRetention(HistoryRetention.NONE)
            session.start()
            assertTrue("Capture was gated by model preparation", capture.started.await(5, TimeUnit.SECONDS))
            assertTrue("Input must keep arriving after model failure", capture.sent.await(5, TimeUnit.SECONDS))
            assertFalse("Recognition failure must wait for the user to stop capture", completed.isCompleted)
            session.stop()
            assertNotNull(withTimeout(10000) { completed.await() })
            val recovery = app.recordingHistory.list().first { it.samples >= 8000 }
            assertArrayEquals(ShortArray(8000) { 1234 }, readAll(app, recovery.id, 8000))
            app.recordingHistory.delete(recovery.id)
        } finally {
            session.cancel()
            scope.cancel()
            app.settingsRepository.setHistoryRetention(oldRetention)
        }
    }

    private fun readAll(app: UtterlaneApp, id: String, count: Int): ShortArray {
        val result = ShortArray(count)
        var offset = 0
        while (offset < count) {
            val block = app.recordingHistory.read(id, offset.toLong(), minOf(3200, count - offset))
            block.copyInto(result, offset)
            offset += block.size
        }
        return result
    }

    private class CountingCapture : AudioCapture {
        val started = CountDownLatch(1)
        val sent = CountDownLatch(1)
        private val stopped = AtomicBoolean(false)
        private var observer: CaptureObserver? = null
        override fun setObserver(observer: CaptureObserver) { this.observer = observer }
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            observer?.onStarted()
            started.countDown()
            repeat(10) {
                if (stopped.get() || !shouldContinue()) return
                onSamples(ShortArray(800) { 1234 })
                Thread.sleep(20)
            }
            sent.countDown()
            while (!stopped.get() && shouldContinue()) Thread.sleep(10)
        }
        override fun stop() { stopped.set(true) }
    }
}
