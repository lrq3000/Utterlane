package io.github.lrq3000.utterlane

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.history.*
import io.github.lrq3000.utterlane.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TranscriptionDialogAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp

    @Test fun manualAudioSavePinsAndDeduplicatesWhileClosingTemporaryAudioDiscards() = runBlocking {
        val recording = app.recordingHistory.begin(HistoryRetention.NONE)
        recording.append(ShortArray(1600) { 1000 }); recording.finish(true)
        val store = ViewModelStore()
        lateinit var model: TranscriptionDialogModel
        instrumentation.runOnMainSync {
            model = TranscriptionDialogModel(app, DialogInput(audioId = recording.entry.id))
            store.put("dialog", model)
        }
        try {
            withTimeout(5000) { model.state.first { !it.importing && it.audio != null } }
            instrumentation.runOnMainSync { model.saveAudioToHistory() }
            withTimeout(5000) { model.state.first { !it.saving && it.audio?.pinned == true } }
            instrumentation.runOnMainSync { model.saveAudioToHistory() }
            withTimeout(5000) { while (model.state.value.saving) delay(10) }
            assertEquals(1, app.recordingHistory.list().count { it.id == recording.entry.id })
            val closed = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { model.dismiss { closed.complete(Unit) } }
            withTimeout(5000) { closed.await() }
            app.recordingHistory.prune(HistoryRetention.NONE)
            assertTrue(recording.entry.directory.exists())
            assertTrue(app.recordingHistory.get(recording.entry.id).pinned)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            app.recordingHistory.delete(recording.entry.id)
        }
    }

    @Test fun importedAudioCloseDeletesOnlyThePrivateCopy() = runBlocking {
        val original = File.createTempFile("original-", ".wav", app.cacheDir)
        WavFile(original).use { it.append(ShortArray(1600) { 1000 }) }
        val owners = ViewModelStore()
        lateinit var model: TranscriptionDialogModel
        instrumentation.runOnMainSync {
            model = TranscriptionDialogModel(app, DialogInput(path = original.absolutePath))
            owners.put("dialog", model)
        }
        try {
            val loaded = withTimeout(5000) { model.state.first { !it.importing && it.audio != null } }
            assertArrayEquals(original.readBytes(), loaded.audio!!.part(0).readBytes())
            val closed = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { model.dismiss { closed.complete(Unit) } }
            withTimeout(5000) { closed.await() }
            assertFalse(loaded.audio!!.directory.exists())
            assertTrue(original.isFile)
        } finally { instrumentation.runOnMainSync { owners.clear() }; original.delete() }
    }

    @Test fun failedRetryKeepsTheSameAudioUntilExplicitDismissal() = runBlocking {
        assertFalse("Use the model-free QA identity for this test", app.modelManager.isModelReady())
        val recording = app.recordingHistory.begin(HistoryRetention.NONE)
        recording.append(ShortArray(1600)); recording.finish(true)
        val owners = ViewModelStore()
        lateinit var model: TranscriptionDialogModel
        instrumentation.runOnMainSync {
            model = TranscriptionDialogModel(app, DialogInput(audioId = recording.entry.id))
            owners.put("dialog", model)
        }
        try {
            withTimeout(5000) { model.state.first { !it.importing } }
            instrumentation.runOnMainSync { model.retry() }
            withTimeout(10000) { model.state.first { !it.running && it.capture.phase == io.github.lrq3000.utterlane.asr.CapturePhase.FAILED } }
            assertTrue(recording.entry.directory.exists())
            val done = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { model.dismiss { done.complete(Unit) } }
            withTimeout(5000) { done.await() }
            assertFalse(recording.entry.directory.exists())
        } finally { instrumentation.runOnMainSync { owners.clear() }; app.recordingHistory.delete(recording.entry.id) }
    }
}
