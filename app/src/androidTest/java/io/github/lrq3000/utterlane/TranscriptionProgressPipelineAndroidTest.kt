package io.github.lrq3000.utterlane

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.history.HistoryRetention
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import io.github.lrq3000.utterlane.transcribe.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs real native window processing through the actual dialog owner. Synthetic
 * PCM checks progress plumbing, not speech accuracy or benchmark performance.
 * Requires the existing catalog-verified on-device fixture; never downloads it. */
@RunWith(AndroidJUnit4::class)
class TranscriptionProgressPipelineAndroidTest {
    @Test fun realFileOperationPublishesMeasuredProgressAndCompletesOnlyAtTheEnd() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val previousModel = app.modelManager.selected.value
        val previousOptions = app.settingsRepository.runtimeOptions.first()
        val previousSpeakers = app.settingsRepository.diarizationEnabled.first()
        val previousTextRetention = app.settingsRepository.transcriptHistoryEnabled.first()
        val owners = ViewModelStore()
        val definition = ModelCatalog.find("parakeet-redux-tq1-q8-native")
        val target = File(app.modelManager.directory(definition), "model.gguf")
        val existed = target.exists()
        var model: TranscriptionDialogModel? = null
        var collector: Job? = null
        val recording = app.recordingHistory.begin(HistoryRetention.NONE)
        recording.append(ShortArray(30 * 16000) { if (it % 80 < 40) 1000 else -1000 }); recording.finish(true)
        try {
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(definition)
            if (!existed) {
                instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.READ_EXTERNAL_STORAGE")
                target.parentFile!!.mkdirs()
                File("/sdcard/Download/parakeet-qa/parakeet-redux-0.6b-TQ1_Q8_0.gguf").copyTo(target)
            }
            assertTrue("The local native QA model must pass its catalog checksum", app.modelManager.ensureVerified())
            app.settingsRepository.setDiarizationEnabled(false)
            app.settingsRepository.setTranscriptHistoryEnabled(false)
            app.settingsRepository.setRuntimeOptions(RuntimeOptions(asrWindowSeconds = 5.0, asrMinSeconds = 5.0))
            instrumentation.runOnMainSync {
                model = TranscriptionDialogModel(app, DialogInput(audioId = recording.entry.id))
                owners.put("progress", model!!)
            }
            val owner = model!!
            withTimeout(5000) { owner.state.first { !it.importing } }
            var measured: FileProgressSnapshot? = null
            collector = launch(start = CoroutineStart.UNDISPATCHED) {
                owner.state.collect { state ->
                    state.fileProgress?.takeIf { it.remainingSeconds != null && it.percent in 1..99 }?.let { measured = it }
                }
            }
            instrumentation.runOnMainSync { owner.retry() }
            val result = withTimeout(180000) { owner.state.first { !it.running && it.fileProgress != null } }
            assertEquals(result.message, FileProgressStage.COMPLETE, result.fileProgress!!.stage)
            assertEquals(100, result.fileProgress!!.percent)
            assertEquals(480000L, result.fileProgress!!.processedSamples)
            assertEquals(480000L, result.fileProgress!!.totalSamples)
            assertFalse(result.fileProgress!!.estimatedTotal)
            assertNotNull("Real completed windows must produce a measurable in-flight ETA", measured)
            assertTrue(measured!!.remainingSeconds!! > 0)
        } finally {
            collector?.cancelAndJoin()
            instrumentation.runOnMainSync { owners.clear() }
            app.recognizerManager.forceUnload()
            model?.state?.value?.store?.file?.let(TranscriptStore::deleteArtifacts)
            app.recordingHistory.delete(recording.entry.id)
            app.settingsRepository.setRuntimeOptions(previousOptions)
            app.settingsRepository.setDiarizationEnabled(previousSpeakers)
            app.settingsRepository.setTranscriptHistoryEnabled(previousTextRetention)
            app.recognizerManager.selectModel(previousModel)
            if (!existed) target.delete()
        }
    }
}
