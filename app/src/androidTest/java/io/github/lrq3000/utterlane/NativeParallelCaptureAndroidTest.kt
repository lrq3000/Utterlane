package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.history.HistoryRetention
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real cold native loading/inference; microphone input is the bundled public-domain excerpt. */
@RunWith(AndroidJUnit4::class)
class NativeParallelCaptureAndroidTest {
    @Test fun coldModelLoadsAfterCaptureStartsAndTranscribesTheCompleteBacklog(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        assertTrue(app.packageName.endsWith(".parallelcapture"))
        val model = ModelCatalog.REDUX_TERNARY
        val previousModel = app.modelManager.selected.value
        val previousRetention = app.settingsRepository.historyRetention.first()
        val previousDiarization = app.settingsRepository.diarizationEnabled.first()
        ParallelCaptureFixtures.installTernary(app)
        val pcm = ParallelCaptureFixtures.speech(app)
        val capture = ExcerptCapture(pcm) { app.recognizerManager.isInitialized() }
        val complete = CompletableDeferred<Pair<TranscriptStore?, SessionFailure?>>()
        val closed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val microphone = MicrophoneSession(app, scope, onText = { _, _ -> },
            onComplete = { store, failure -> complete.complete(store to failure) },
            recorder = capture, onSessionClosed = { closed.complete(Unit) })
        var result: TranscriptStore? = null
        try {
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(model)
            app.settingsRepository.setHistoryRetention(HistoryRetention.NONE)
            app.settingsRepository.setDiarizationEnabled(false)
            assertFalse(app.recognizerManager.isInitialized())
            microphone.start()
            assertTrue("Capture waited for cold model loading", capture.delivered.await(3, TimeUnit.SECONDS))
            assertFalse("The model was already ready when capture started", capture.modelReadyAtStart)
            microphone.stop()
            val (store, failure) = withTimeout(120000) { complete.await() }
            result = store
            assertNull(failure?.message, failure)
            assertNotNull(store)
            val text = withContext(Dispatchers.IO) { store!!.readForTransfer() }
            assertTrue("Missing expected speech: $text", text?.contains("Alice", ignoreCase = true) == true)
            assertEquals(pcm.size.toLong(), microphone.metrics.state.value.capturedSamples)
            assertEquals(pcm.size.toLong(), microphone.metrics.state.value.processedSamples)
            withTimeout(5000) { closed.await() }
            assertTrue("Successful No-history capture leaked temporary audio",
                File(app.noBackupFilesDir, "microphone-temporary").listFiles().orEmpty().isEmpty())
            android.util.Log.i("ParallelCaptureTest", "Cold native load followed capture; all ${pcm.size} samples processed; text=$text")
        } finally {
            microphone.cancel(); scope.cancel()
            result?.let { withContext(Dispatchers.IO) { it.dispose() } }
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(previousModel)
            app.settingsRepository.setHistoryRetention(previousRetention)
            app.settingsRepository.setDiarizationEnabled(previousDiarization)
        }
    }

    private class ExcerptCapture(private val pcm: ShortArray, private val modelReady: () -> Boolean) : AudioCapture {
        val delivered = CountDownLatch(1)
        private val stopped = AtomicBoolean(false)
        @Volatile var modelReadyAtStart = true
        private var observer: CaptureObserver? = null
        override fun setObserver(observer: CaptureObserver) { this.observer = observer }
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            modelReadyAtStart = modelReady()
            observer?.onStarted()
            var offset = 0
            while (offset < pcm.size && !stopped.get() && shouldContinue()) {
                val end = minOf(offset + 3200, pcm.size)
                onSamples(pcm.copyOfRange(offset, end))
                offset = end
            }
            delivered.countDown()
            while (!stopped.get() && shouldContinue()) Thread.sleep(5)
        }
        override fun stop() { stopped.set(true) }
    }
}
