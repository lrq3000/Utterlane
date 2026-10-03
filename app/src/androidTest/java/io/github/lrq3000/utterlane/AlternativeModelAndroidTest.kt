package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.CrispParakeetBackend
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.transcribe.AudioDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlternativeModelAndroidTest {
    @Before fun permission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, "android.permission.READ_EXTERNAL_STORAGE")
    }
    @Test fun bothMoondreamModelsRunThroughNativeAndroidRuntime(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val blocks = mutableListOf<ShortArray>()
        AudioDecoder(context).decode("/sdcard/Download/speech-source.wav", { blocks.add(it) })
        val pcm = ShortArray(blocks.sumOf { it.size })
        var offset = 0
        blocks.forEach { it.copyInto(pcm, offset); offset += it.size }
        for (name in listOf("parakeet-ultra-q8_0.gguf", "parakeet-redux-q8_0.gguf")) {
            val path = File("/sdcard/Download/parakeet-qa", name)
            assertTrue("Missing alternative fixture $path", path.isFile)
            CrispParakeetBackend(path.absolutePath).use { backend ->
                val result = backend.transcribeWindow(pcm)
                assertEquals(result.tokens.size, result.timestamps.size)
                assertTrue(result.tokens.joinToString("").contains("country", true))
                assertTrue(result.timestamps.all { it >= 0 && it < 5 })
            }
        }
    }
    @Test fun catalogSelectionPersistsAndActiveSessionsBlockSwitching(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as UtterlaneApp
        val previous = app.modelManager.selected.value
        try {
            val redux = ModelCatalog.find("parakeet-redux-q8_0")
            app.recognizerManager.selectModel(redux)
            val directory = app.modelManager.directory(redux).apply { mkdirs() }
            File("/sdcard/Download/parakeet-qa/parakeet-redux-q8_0.gguf").copyTo(File(directory, "model.gguf"), overwrite = true)
            assertTrue(app.modelManager.ensureVerified())
            assertEquals(redux.id, app.settingsRepository.selectedModelId.first())
            val otherManager = io.github.lrq3000.utterlane.asr.ModelManager(context)
            otherManager.initializeSelection()
            assertEquals(redux, otherManager.selected.value)
            val session = app.recognizerManager.createSession()
            try {
                var rejected = false
                try { app.recognizerManager.selectModel(ModelCatalog.DEFAULT) }
                catch (_: IllegalStateException) { rejected = true }
                assertTrue("Switching freed an active model", rejected)
            } finally { session.close(); session.store.dispose() }
            app.recognizerManager.deleteSelectedModel()
            assertFalse(app.modelManager.isModelReady(redux))
            app.recognizerManager.selectModel(ModelCatalog.DEFAULT)
        } finally { app.recognizerManager.selectModel(previous) }
    }
    @Test fun cancellingBlockedNetworkIoRestoresDownloadControls(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as UtterlaneApp
        val entered = java.util.concurrent.CountDownLatch(1)
        val finishIo = java.util.concurrent.CountDownLatch(1)
        val client = okhttp3.OkHttpClient.Builder().addInterceptor {
            entered.countDown()
            finishIo.await(10, java.util.concurrent.TimeUnit.SECONDS)
            throw java.io.IOException("Simulated socket cancellation")
        }.build()
        val manager = io.github.lrq3000.utterlane.asr.ModelManager(context, client)
        val previous = app.settingsRepository.selectedModelId.first()
        try {
            manager.select(ModelCatalog.find("parakeet-ultra-q4_k"))
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch { manager.downloadModel() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            manager.cancelTransfer(); finishIo.countDown(); job.join()
            assertFalse(manager.isTransferring)
            assertEquals(io.github.lrq3000.utterlane.asr.ModelManager.DownloadState.NotStarted, manager.downloadState.value)
            assertFalse(manager.directory().parentFile!!.listFiles()!!.any { it.name.startsWith(".parakeet-ultra-q4_k-") })
        } finally { finishIo.countDown(); app.settingsRepository.setSelectedModelId(previous) }
    }
}
