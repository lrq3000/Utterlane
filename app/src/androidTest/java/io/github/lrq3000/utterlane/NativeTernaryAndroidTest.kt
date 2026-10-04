package io.github.lrq3000.utterlane

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real GGUF + CPU kernels, not a mock of the new backend. Fixtures stay outside the APK. */
@RunWith(AndroidJUnit4::class)
class NativeTernaryAndroidTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
    private val fixtures get() = File(app.getExternalFilesDir(null), "ternary-qa")
    private val artifact get() = File(fixtures, "parakeet-redux-0.6b-TQ1_Q8_0.gguf")

    @Before fun fixturesAvailable() {
        // App-specific fixtures need no broad storage permission, including on
        // API 33+. Avoid UiAutomation, which is a singleton across QA packages.
        assertTrue("Push the compact GGUF to $fixtures before instrumentation", artifact.isFile)
        assertTrue("Push speech-source.wav to $fixtures", File(fixtures, "speech-source.wav").isFile)
    }

    private suspend fun spokenPcm(): ShortArray {
        val pieces = mutableListOf<ShortArray>()
        AudioDecoder(app).decode(File(fixtures, "speech-source.wav").absolutePath, { pieces.add(it) })
        val pcm = ShortArray(pieces.sumOf { it.size })
        var offset = 0
        for (piece in pieces) { piece.copyInto(pcm, offset); offset += piece.size }
        return pcm
    }

    @Test fun nativeTernaryWeightsStayPackedAcrossRepeatedWindows(): Unit = runBlocking {
        assertTrue(ArtifactVerifier.valid(artifact, ModelCatalog.REDUX_TERNARY.artifacts.single()))
        val pcm = spokenPcm()
        // A caller/environment override must not silently select expanded weights.
        android.system.Os.setenv("TRANSCRIBE_TERNARY_RUNTIME", "q4_0", true)
        val started = SystemClock.elapsedRealtime()
        TranscribeCppBackend(artifact.absolutePath).use { backend ->
            val layout = backend.weightLayout()
            assertEquals(264L, layout.ternaryTensors)
            assertTrue(layout.ternaryBytes in 120_000_000L..140_000_000L)
            assertTrue(layout.tensorBytes < 159_121_504L)
            val loadedMs = SystemClock.elapsedRealtime() - started
            val loadedHeap = Debug.getNativeHeapAllocatedSize()
            repeat(2) {
                val result = backend.transcribeWindow(pcm)
                assertEquals(result.tokens.size, result.timestamps.size)
                assertTrue(result.timestamps.all { time -> time.isFinite() && time >= 0 && time < 5 })
                val text = WindowText.select(result.tokens, result.timestamps, AudioWindow(pcm, 0, 0, pcm.size.toLong()))
                assertTrue("Missing spoken content: $text", text.contains("country", true))
                assertFalse("SentencePiece markers leaked into output", text.contains('▁'))
            }
            val fullWindow = ShortArray(192000) { pcm[it % pcm.size] }
            val result = backend.transcribeWindow(fullWindow)
            assertTrue(result.tokens.joinToString("").contains("country", true))
            assertEquals(layout, backend.weightLayout())
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val evidence = "tensors=${layout.ternaryTensors}, ternaryBytes=${layout.ternaryBytes}, tensorBytes=${layout.tensorBytes}, loadMs=$loadedMs, loadNativeHeap=$loadedHeap, finalNativeHeap=${Debug.getNativeHeapAllocatedSize()}, finalPssKb=${memory.totalPss}, totalMs=${SystemClock.elapsedRealtime() - started}"
            File(app.filesDir, "ternary-qa.txt").writeText(evidence)
            android.util.Log.i("NativeTernaryTest", evidence)
        }
    }

    @Test fun workerSwitchesBackendsAndForceUnloadKeepsCompactWeightsOnDisk(): Unit = runBlocking {
        app.modelManager.initializeSelection()
        val previous = app.modelManager.selected.value
        val pcm = spokenPcm()
        try {
            for (model in listOf(ModelCatalog.DEFAULT, ModelCatalog.REDUX_TERNARY, ModelCatalog.PARAKEET_V3)) {
                app.recognizerManager.forceUnload()
                app.recognizerManager.selectModel(model)
                val directory = app.modelManager.directory(model).apply { mkdirs() }
                for (part in model.artifacts) {
                    val target = File(directory, part.localName)
                    if (!target.exists()) {
                        val sourceName = when (model.backend) {
                            ModelBackend.SHERPA -> part.localName
                            ModelBackend.CRISP -> "${model.id}.gguf"
                            ModelBackend.TRANSCRIBE_CPP -> artifact.name
                        }
                        File(fixtures, sourceName).copyTo(target)
                    }
                }
                assertTrue(app.recognizerManager.initialize())
                val session = app.recognizerManager.createSession()
                try {
                    session.accept(pcm); session.finish()
                    assertTrue(session.store.readForTransfer()!!.contains("country", true))
                } finally { session.close(); session.store.dispose() }
                withContext(Dispatchers.Main) { app.recognizerManager.forceUnload() }
                assertFalse(app.recognizerManager.isReady.value)
            }
            val compact = File(app.modelManager.directory(ModelCatalog.REDUX_TERNARY), "model.gguf")
            assertEquals(159121504L, compact.length())
        } finally { app.recognizerManager.forceUnload(); app.recognizerManager.selectModel(previous) }
    }
}
