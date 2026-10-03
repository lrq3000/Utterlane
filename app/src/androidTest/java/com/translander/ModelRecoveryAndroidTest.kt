package com.translander

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.translander.asr.*
import com.translander.transcribe.AudioDecoder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ModelRecoveryAndroidTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TranslanderApp

    private suspend fun prepare(id: String): ModelDefinition {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.READ_EXTERNAL_STORAGE")
        val model = ModelCatalog.find(id)
        app.recognizerManager.forceUnload()
        app.recognizerManager.selectModel(model)
        val directory = app.modelManager.directory(model).apply { mkdirs() }
        val source = File("/sdcard/Download/parakeet-qa", "$id.gguf")
        val target = File(directory, "model.gguf")
        if (!target.exists()) source.copyTo(target)
        assertTrue(app.modelManager.ensureVerified())
        return model
    }

    @Test fun q8ModelsValidateAndTranscribeNormalWindowsInWorker(): Unit = runBlocking {
        val pieces = mutableListOf<ShortArray>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, "android.permission.READ_EXTERNAL_STORAGE")
        AudioDecoder(app).decode("/sdcard/Download/speech-source.wav", { pieces.add(it) })
        val seed = ShortArray(pieces.sumOf { it.size })
        var offset = 0
        pieces.forEach { it.copyInto(seed, offset); offset += it.size }
        for (id in listOf("parakeet-ultra-q8_0", "parakeet-redux-q8_0")) {
            prepare(id)
            val start = android.os.SystemClock.elapsedRealtime()
            assertTrue(app.recognizerManager.initialize())
            assertTrue(app.recognizerManager.isReady.value)
            assertNull(app.recognizerManager.failure.value)
            val session = app.recognizerManager.createSession()
            try {
                // Earlier smoke tests only exercised a 3.8-second utterance. Use
                // a full 12-second inference window and another same-sized one.
                repeat(2) { session.accept(ShortArray(192000) { seed[it % seed.size] }) }
                session.finish()
                assertTrue(session.store.readForTransfer()!!.contains("country", true))
            } finally { session.close(); session.store.dispose(); app.recognizerManager.forceUnload() }
            assertFalse(app.recognizerManager.isReady.value)
            android.util.Log.i("ModelRecoveryTest", "$id: validated load plus 24 seconds completed in ${android.os.SystemClock.elapsedRealtime() - start} ms")
        }
    }

    @Test fun forceUnloadBreaksBlockedLoadAndStaleLoadCannotBecomeReady(): Unit = runBlocking {
        prepare("parakeet-ultra-q8_0")
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val manager = RecognizerManager(app, app.modelManager) { _, _ ->
            object : RecognitionBackend {
                override fun prepare() {
                    entered.countDown()
                    released.await(10, TimeUnit.SECONDS)
                }
                override fun transcribeWindow(samples: ShortArray) = WindowResult(emptyArray(), FloatArray(0))
                override fun close() { closed.set(true); released.countDown() }
            }
        }
        val load = async(Dispatchers.IO) { manager.initialize() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val start = System.nanoTime()
        manager.forceUnload()
        assertTrue("Reset waited on the blocked model lock", (System.nanoTime() - start) / 1000000 < 500)
        assertFalse(load.await())
        assertTrue(closed.get())
        assertFalse(manager.isReady.value)
        assertFalse(manager.isLoading.value)
        manager.selectModel(ModelCatalog.DEFAULT)
    }

    @Test fun loadFailureIsVisibleAndResetLeavesSwitchingAvailable(): Unit = runBlocking {
        prepare("parakeet-ultra-q8_0")
        val manager = RecognizerManager(app, app.modelManager) { _, _ -> throw UnsatisfiedLinkError("test missing native dependency") }
        assertFalse(manager.initialize())
        assertTrue(manager.failure.value!!.contains("missing native dependency"))
        assertFalse(manager.isReady.value)
        manager.forceUnload()
        manager.selectModel(ModelCatalog.DEFAULT)
        assertNull(manager.failure.value)
    }

    @Test fun workerCanBeTerminatedDuringNativeInference(): Unit = runBlocking {
        prepare("parakeet-ultra-q8_0")
        val backend = WorkerRecognitionBackend(app, ModelCatalog.find("parakeet-ultra-q8_0"))
        backend.prepare()
        assertTrue(backend.workerPid > 0 && backend.workerPid != android.os.Process.myPid())
        val entered = CompletableDeferred<Unit>()
        val inference = async(Dispatchers.IO) {
            entered.complete(Unit)
            runCatching { backend.transcribeWindow(ShortArray(192000) { 1000 }) }
        }
        entered.await(); delay(50)
        backend.close()
        withTimeout(3000) { inference.await() }
        assertEquals(0, backend.workerPid)
    }

    @Test fun failedDecodeRetryKeepsWorkerReachableForReset(): Unit = runBlocking {
        prepare("parakeet-ultra-q8_0")
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val manager = RecognizerManager(app, app.modelManager) { _, _ ->
            object : RecognitionBackend {
                override fun transcribeWindow(samples: ShortArray): WindowResult = error("Injected decode failure")
                override fun close() { closed.set(true) }
            }
        }
        val session = manager.createSession()
        try {
            assertTrue(runCatching { session.accept(ShortArray(192000)) }.isFailure)
            assertFalse(manager.initialize()) // Another input retries before the old session drains.
            assertFalse(closed.get())
        } finally { session.close(); session.store.dispose() }
        manager.forceUnload()
        assertTrue("Retry orphaned the failed model", closed.get())
    }

    @Test fun idleWorkerDeathClearsReadinessAndAllowsReload(): Unit = runBlocking {
        prepare("parakeet-ultra-q8_0")
        var current: WorkerRecognitionBackend? = null
        val manager = RecognizerManager(app, app.modelManager) { model, _ -> WorkerRecognitionBackend(app, model).also { current = it } }
        try {
            assertTrue(manager.initialize())
            val pid = current!!.workerPid
            android.os.Process.killProcess(pid)
            withTimeout(3000) { while (manager.isReady.value) delay(10) }
            assertNotNull(manager.failure.value)
            assertTrue(manager.initialize())
            assertNotEquals(pid, current!!.workerPid)
        } finally { manager.forceUnload() }
    }

    @Test fun resetBeforeDispatchStillCompletesMicrophoneOwner(): Unit = runBlocking {
        repeat(5) {
            val callbacks = java.util.concurrent.atomic.AtomicInteger(0)
            val complete = CountDownLatch(1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val session = MicrophoneSession(app, scope, onText = { _, _ -> }, onComplete = { _, _ -> callbacks.incrementAndGet(); complete.countDown() })
                session.start()
                MicrophoneSession.resetActive()
            }
            try {
                assertTrue("Reset did not release the UI owner", complete.await(5, TimeUnit.SECONDS))
                assertEquals(1, callbacks.get())
                assertFalse(MicrophoneSession.isBusy())
            } finally { scope.cancel() }
        }
    }
}
