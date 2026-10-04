package io.github.lrq3000.utterlane

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.settings.AppLanguage
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CrispStreamingAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as UtterlaneApp

    // Grant storage with adb before running; UiAutomation is emulator-global and
    // would compete with another worktree's UI test even with separate app IDs.
    private suspend fun audio(path: String): ShortArray {
        val blocks = mutableListOf<ShortArray>()
        AudioDecoder(context).decode(path, { blocks += it })
        val pcm = ShortArray(blocks.sumOf { it.size })
        var at = 0
        for (block in blocks) { block.copyInto(pcm, at); at += block.size }
        return pcm
    }

    private suspend fun installDiarizer() {
        val model = File(app.diarizationModels.directory().apply { mkdirs() }, "model.gguf")
        if (!model.exists()) File("/sdcard/Download/Nemotron-3-Diarization.q8_0.gguf").copyTo(model)
        assertTrue(app.diarizationModels.ensureVerified())
    }

    @Test fun customImportPersistsAndStreamsWithAndWithoutSpeakers(): Unit = runBlocking {
        app.modelManager.initializeSelection()
        val previous = app.modelManager.selected.value
        val oldEnabled = app.settingsRepository.diarizationEnabled.first()
        val oldCount = app.settingsRepository.speakerCount.first()
        var imported: ModelDefinition? = null
        try {
            app.recognizerManager.forceUnload()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File("/sdcard/Download/parakeet-qa/parakeet-ultra-q8_0.gguf"))
            imported = app.modelManager.importCustom(listOf(uri), uri)
            app.recognizerManager.selectModel(imported)
            val restored = ModelManager(context)
            restored.initializeSelection()
            assertEquals(imported, restored.selected.value)
            assertTrue(restored.ensureVerified())
            assertTrue("Generic native warm-up failed: ${app.recognizerManager.failure.value}", app.recognizerManager.initialize())
            installDiarizer()
            val pcm = audio("/sdcard/Download/speech-source.wav")
            for (enabled in listOf(false, true)) {
                app.settingsRepository.setDiarizationEnabled(enabled)
                app.settingsRepository.setSpeakerCount(0)
                val session = app.recognizerManager.createSession()
                try {
                    var at = 0
                    while (at < pcm.size) { val end = minOf(at + 1600, pcm.size); session.accept(pcm.copyOfRange(at, end)); at = end }
                    session.finish()
                    val text = session.store.readForTransfer()!!
                    android.util.Log.i("CrispStreamingTest", "diarization=$enabled: $text")
                    assertTrue(text, text.contains("country", true))
                    assertEquals(text, enabled, text.contains(Regex("Speaker [1-8]:|Unknown speaker:")))
                } finally { session.close(); session.store.dispose() }
            }
        } finally {
            app.recognizerManager.forceUnload()
            if (imported != null && app.modelManager.selected.value.id == imported.id) app.recognizerManager.deleteSelectedModel()
            app.recognizerManager.selectModel(previous)
            app.settingsRepository.setDiarizationEnabled(oldEnabled)
            app.settingsRepository.setSpeakerCount(oldCount)
        }
    }

    @Test fun originalOnnxEmitsSpeakerLabelsBeforeRecordingFinishes(): Unit = runBlocking {
        app.modelManager.initializeSelection()
        val previous = app.modelManager.selected.value
        val oldEnabled = app.settingsRepository.diarizationEnabled.first()
        val oldCount = app.settingsRepository.speakerCount.first()
        try {
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(ModelCatalog.PARAKEET_V3)
            val directory = app.modelManager.directory().apply { mkdirs() }
            for (artifact in ModelCatalog.PARAKEET_V3.artifacts) {
                val target = File(directory, artifact.localName)
                if (!target.exists()) File("/sdcard/Download/parakeet-qa", artifact.localName).copyTo(target)
            }
            installDiarizer()
            app.settingsRepository.setDiarizationEnabled(true)
            app.settingsRepository.setSpeakerCount(2)
            val fixture = audio("/sdcard/Download/speech-source.wav")
            // Continuous speech emits at the existing 10 s ownership boundary
            // plus 1 s right context; the fixture itself is only ~3.85 seconds.
            val pcm = fixture + fixture + fixture
            var liveSegments = 0
            val session = app.recognizerManager.createSession(onSegment = { liveSegments++ })
            try {
                var at = 0
                while (at < pcm.size) { val stop = minOf(at + 1600, pcm.size); session.accept(pcm.copyOfRange(at, stop)); at = stop }
                assertTrue("No speaker-labeled streaming output before finish", liveSegments > 0)
                session.finish()
                val text = session.store.readForTransfer()!!
                android.util.Log.i("CrispStreamingTest", "ONNX live segments=$liveSegments, fixed speakers=2: $text")
                assertTrue(text, text.contains("country", true))
                assertTrue(text, text.contains("Speaker 1:"))
                assertFalse(text, text.contains("Speaker 3:"))
            } finally { session.close(); session.store.dispose() }
        } finally {
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(previous)
            app.settingsRepository.setDiarizationEnabled(oldEnabled)
            app.settingsRepository.setSpeakerCount(oldCount)
        }
    }

    @Test fun nativeStreamingTracksSeveralVoicesAndFlushes(): Unit = runBlocking {
        val full = audio("/sdcard/Download/nemotron3_tts_8_open_voices.mp4")
        val pcm = full.copyOf(minOf(full.size, 20 * 16000))
        val timeline = SpeakerTimeline(0)
        val seen = mutableSetOf<Int>()
        var emitted = 0L
        CrispSpeakerStream("/sdcard/Download/Nemotron-3-Diarization.q8_0.gguf").use { stream ->
            var at = 0
            while (at < pcm.size) {
                val stop = minOf(at + 16000, pcm.size)
                val output = stream.push(pcm.copyOfRange(at, stop), stop == pcm.size)
                timeline.discardBefore(emitted)
                timeline.append(output)
                while (emitted < timeline.endSample) {
                    val speaker = timeline.speakerAt(emitted)
                    if (speaker >= 0) seen += speaker
                    emitted += 160
                }
                at = stop
            }
        }
        android.util.Log.i("CrispStreamingTest", "Native multi-voice fixture: samples=${pcm.size}, frames=${emitted / 160}, speakers=$seen")
        assertTrue("No multi-speaker evidence: $seen", seen.size >= 2)
        assertTrue("Missing flush: $emitted / ${pcm.size}", kotlin.math.abs(emitted - pcm.size) <= 320)
    }

    @Test fun genericDispatcherAlsoTranscribesWhisperGgml(): Unit = runBlocking {
        val pcm = audio("/sdcard/Download/speech-source.wav")
        CrispGenericBackend("/sdcard/Download/ggml-tiny.en-q5_1.bin").use { backend ->
            val text = backend.transcribeWindow(pcm).text!!
            android.util.Log.i("CrispStreamingTest", "Generic Whisper: $text")
            assertTrue(text, text.contains("country", true))
        }
    }

    @Test fun nativeFinalBoundaryFlushesWithoutFutureAudio(): Unit = runBlocking {
        val fixture = audio("/sdcard/Download/speech-source.wav")
        val pcm = ShortArray(160000) { fixture[it % fixture.size] }
        val spans = mutableListOf<SpeechSpan>()
        val backend = object : RecognitionBackend {
            override fun transcribeWindow(samples: ShortArray) = WindowResult(arrayOf(" boundary"), floatArrayOf(9.9f))
            override fun close() {}
        }
        DiarizedWindowProcessor(backend, CrispSpeakerStream("/sdcard/Download/Nemotron-3-Diarization.q8_0.gguf"), 0).use { processor ->
            val segmenter = AudioSegmenter(flushPendingOnFinish = true) { spans += processor.process(it) }
            segmenter.accept(pcm)
            assertTrue(spans.isEmpty())
            segmenter.finish()
            assertEquals("boundary", spans.single().text)
        }
    }

    @Test fun ternaryDiarizationRespectsImmediateIdleUnload(): Unit = runBlocking {
        app.modelManager.initializeSelection()
        val previous = app.modelManager.selected.value
        val oldEnabled = app.settingsRepository.diarizationEnabled.first()
        val oldCount = app.settingsRepository.speakerCount.first()
        val oldTimeout = app.settingsRepository.modelIdleTimeout.first()
        try {
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(ModelCatalog.REDUX_TERNARY)
            val target = File(app.modelManager.directory().apply { mkdirs() }, "model.gguf")
            if (!target.exists()) File("/sdcard/Download/parakeet-redux-0.6b-TQ1_Q8_0.gguf").copyTo(target)
            installDiarizer()
            app.settingsRepository.setDiarizationEnabled(true)
            app.settingsRepository.setSpeakerCount(2)
            app.settingsRepository.setModelIdleTimeout(ModelIdleTimeout.IMMEDIATE)
            app.recognizerManager.setIdleTimeout(ModelIdleTimeout.IMMEDIATE)
            val session = app.recognizerManager.createSession()
            try {
                assertTrue("Immediate idle unloading interrupted session creation", app.recognizerManager.isReady.value)
                val processes = app.getSystemService(android.app.ActivityManager::class.java)
                val pid = processes.runningAppProcesses.single { it.processName == "${app.packageName}:recognition" }.pid
                session.accept(audio("/sdcard/Download/speech-source.wav"))
                assertTrue("Active speaker session was unloaded", app.recognizerManager.isReady.value)
                session.finish()
                val text = session.store.readForTransfer()!!
                assertTrue(text, text.contains("country", true))
                assertTrue(text, text.contains(context.getString(R.string.speaker_label, 1)))
                kotlinx.coroutines.withTimeout(10000) {
                    while (app.recognizerManager.isReady.value || processes.runningAppProcesses.any { it.pid == pid }) kotlinx.coroutines.delay(10)
                }
                android.util.Log.i("CrispStreamingTest", "Ternary + speakers + immediate idle unload: $text")
            } finally { session.close(); session.store.dispose() }
        } finally {
            app.recognizerManager.forceUnload()
            app.recognizerManager.selectModel(previous)
            app.settingsRepository.setDiarizationEnabled(oldEnabled)
            app.settingsRepository.setSpeakerCount(oldCount)
            app.settingsRepository.setModelIdleTimeout(oldTimeout)
        }
    }

    @Test fun localeOverrideAndSystemFallback(): Unit {
        val old = AppLanguage.selected(context)
        try {
            instrumentation.runOnMainSync {
                AppLanguage.set(context, "fr")
                assertEquals("fr", AppLanguage.selected(context))
                assertEquals("fr", AppLanguage.wrap(context).resources.configuration.locales[0].language)
                AppLanguage.set(context, "en")
                assertEquals("en", AppLanguage.wrap(context).resources.configuration.locales[0].language)
                AppLanguage.set(context, "")
                assertEquals("", AppLanguage.selected(context))
                assertEquals(android.content.res.Resources.getSystem().configuration.locales[0].language,
                    AppLanguage.wrap(context).resources.configuration.locales[0].language)
            }
        } finally { instrumentation.runOnMainSync { AppLanguage.set(context, old) } }
    }
}
