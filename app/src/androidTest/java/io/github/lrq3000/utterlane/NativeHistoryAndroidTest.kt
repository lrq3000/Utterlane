package io.github.lrq3000.utterlane

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.transcribe.DialogInput
import io.github.lrq3000.utterlane.transcribe.TranscriptionDialogModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Adapted from parallel-capture fdee5c6: actual native inference, public-domain speech. */
@RunWith(AndroidJUnit4::class)
class NativeHistoryAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp

    private suspend fun fixture(): ShortArray = withContext(Dispatchers.IO) {
        assertTrue(app.packageName.endsWith(".recordingfirst"))
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.READ_EXTERNAL_STORAGE")
        val source = File("/sdcard/Download/parakeet-qa/parakeet-redux-0.6b-TQ1_Q8_0.gguf")
        assertTrue("Provide the pinned native ternary model fixture", source.isFile)
        val target = File(app.modelManager.directory(ModelCatalog.REDUX_TERNARY).apply { mkdirs() }, "model.gguf")
        if (!target.exists()) source.copyTo(target)
        val bytes = app.assets.open("onboarding/alice_wonderland_excerpt.wav").use { it.readBytes() }
        ShortArray((bytes.size - 44) / 2).also { ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }

    @Test fun coldNativeLoadFollowsCaptureAndKeepsTextWithoutKeepingAudio() = runBlocking {
        val pcm = fixture()
        val settings = app.settingsRepository
        val previousModel = app.modelManager.selected.value
        val audio = settings.audioHistoryEnabled.first()
        val text = settings.transcriptHistoryEnabled.first()
        val duration = settings.transcriptHistoryRetention.first()
        val speakers = settings.diarizationEnabled.first()
        val beforeAudio = app.recordingHistory.list().map { it.id }.toSet()
        val beforeText = app.transcriptHistory.list().map { it.id }.toSet()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val source = ExcerptCapture(pcm) { app.recognizerManager.isInitialized() }
        val completed = CompletableDeferred<Pair<TranscriptStore?, SessionFailure?>>()
        val closed = CompletableDeferred<Unit>()
        val session = MicrophoneSession(app, scope, onText = { _, _ -> }, recorder = source,
            onComplete = { store, failure -> completed.complete(store to failure) }, onSessionClosed = { closed.complete(Unit) })
        var store: TranscriptStore? = null
        try {
            app.recognizerManager.forceUnload(); app.recognizerManager.selectModel(ModelCatalog.REDUX_TERNARY)
            settings.setAudioHistoryEnabled(false); settings.setTranscriptHistoryEnabled(true)
            settings.setTranscriptHistoryRetention(HistoryRetention.DAY); settings.setDiarizationEnabled(false)
            session.start()
            assertTrue(source.delivered.await(5, TimeUnit.SECONDS))
            assertFalse(source.readyAtStart)
            session.stop()
            val result = withTimeout(120000) { completed.await() }
            store = result.first
            assertNull(result.second?.message, result.second)
            withTimeout(5000) { closed.await() }
            assertEquals(pcm.size.toLong(), session.metrics.state.value.processedSamples)
            val saved = app.transcriptHistory.list().filter { it.id !in beforeText }
            assertEquals(1, saved.size)
            assertTrue(saved.single().file.readText().contains("Alice", ignoreCase = true))
            assertEquals(beforeAudio, app.recordingHistory.list().map { it.id }.toSet())
        } finally {
            session.cancel(); scope.cancel(); store?.dispose()
            app.transcriptHistory.list().filter { it.id !in beforeText }.forEach { app.transcriptHistory.delete(it.id) }
            app.recognizerManager.forceUnload(); app.recognizerManager.selectModel(previousModel)
            settings.setAudioHistoryEnabled(audio); settings.setTranscriptHistoryEnabled(text)
            settings.setTranscriptHistoryRetention(duration); settings.setDiarizationEnabled(speakers)
        }
    }

    @Test fun nativeRetranscriptionCreatesTwoIndependentResultsAndCloseDiscardsOnlyAudio() = runBlocking {
        val pcm = fixture()
        val previous = app.modelManager.selected.value
        val settings = app.settingsRepository
        val enabled = settings.transcriptHistoryEnabled.first()
        val duration = settings.transcriptHistoryRetention.first()
        val speakers = settings.diarizationEnabled.first()
        val before = app.transcriptHistory.list().map { it.id }.toSet()
        val audio = app.recordingHistory.begin(HistoryRetention.NONE)
        for (offset in pcm.indices step 3200) audio.append(pcm.copyOfRange(offset, minOf(pcm.size, offset + 3200)))
        audio.finish(true)
        val owners = ViewModelStore()
        lateinit var model: TranscriptionDialogModel
        try {
            app.recognizerManager.forceUnload(); app.recognizerManager.selectModel(ModelCatalog.REDUX_TERNARY)
            settings.setTranscriptHistoryEnabled(true); settings.setTranscriptHistoryRetention(HistoryRetention.DAY)
            settings.setDiarizationEnabled(false)
            instrumentation.runOnMainSync {
                model = TranscriptionDialogModel(app, DialogInput(audioId = audio.entry.id))
                owners.put("dialog", model)
            }
            withTimeout(5000) { model.state.first { !it.importing } }
            instrumentation.runOnMainSync { model.retry() }
            val first = withTimeout(120000) { model.state.first { !it.running && (it.transcriptId != null || it.capture.phase == CapturePhase.FAILED) } }
            assertNotNull(first.message, first.transcriptId)
            assertTrue(first.preview.contains("Alice", ignoreCase = true))
            instrumentation.runOnMainSync { model.retry() }
            val second = withTimeout(120000) { model.state.first { !it.running && ((it.transcriptId != null && it.transcriptId != first.transcriptId) || it.capture.phase == CapturePhase.FAILED) } }
            assertNotNull(second.message, second.transcriptId)
            assertNotEquals(first.transcriptId, second.transcriptId)
            assertEquals(2, app.transcriptHistory.list().count { it.id !in before })
            assertTrue(audio.entry.directory.exists())
            val done = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { model.dismiss { done.complete(Unit) } }
            withTimeout(5000) { done.await() }
            assertFalse(audio.entry.directory.exists())
            assertTrue(app.transcriptHistory.get(first.transcriptId!!).file.isFile)
            assertTrue(app.transcriptHistory.get(second.transcriptId!!).file.isFile)
        } finally {
            instrumentation.runOnMainSync { owners.clear() }
            app.recordingHistory.delete(audio.entry.id)
            app.transcriptHistory.list().filter { it.id !in before }.forEach { app.transcriptHistory.delete(it.id) }
            app.recognizerManager.forceUnload(); app.recognizerManager.selectModel(previous)
            settings.setTranscriptHistoryEnabled(enabled); settings.setTranscriptHistoryRetention(duration); settings.setDiarizationEnabled(speakers)
        }
    }

    private class ExcerptCapture(private val pcm: ShortArray, private val ready: () -> Boolean) : AudioCapture {
        val delivered = CountDownLatch(1)
        var readyAtStart = true
        private val stopped = AtomicBoolean(false)
        private var observer: CaptureObserver? = null
        override fun setObserver(observer: CaptureObserver) { this.observer = observer }
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            readyAtStart = ready(); observer?.onStarted()
            for (offset in pcm.indices step 3200) {
                if (stopped.get() || !shouldContinue()) break
                onSamples(pcm.copyOfRange(offset, minOf(pcm.size, offset + 3200)))
            }
            delivered.countDown()
            while (!stopped.get() && shouldContinue()) Thread.sleep(5)
        }
        override fun stop() { stopped.set(true) }
    }
}
