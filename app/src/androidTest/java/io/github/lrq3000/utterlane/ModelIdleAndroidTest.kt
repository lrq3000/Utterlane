package io.github.lrq3000.utterlane

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Uses the same catalog-verified on-device Q8 fixture as ModelRecoveryAndroidTest. */
@RunWith(AndroidJUnit4::class)
class ModelIdleAndroidTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
    private val offset = AtomicLong(0)
    private lateinit var manager: RecognizerManager
    private lateinit var previousModel: ModelDefinition
    private lateinit var previousTimeout: ModelIdleTimeout
    private var previousDiarization = false
    private val backends = mutableListOf<FakeBackend>()
    private var nextPrepare: () -> Unit = {}
    private var failDecode = false

    private class FakeBackend(private val onPrepare: () -> Unit, private val failDecode: Boolean) : RecognitionBackend {
        val closed = CountDownLatch(1)
        override fun prepare() = onPrepare()
        override fun isAvailable() = closed.count > 0
        override fun transcribeWindow(samples: ShortArray): WindowResult {
            check(!failDecode) { "Injected decode failure" }
            return WindowResult(emptyArray(), FloatArray(0))
        }
        override fun close() { closed.countDown() }
    }

    @Before fun prepare(): Unit = runBlocking {
        previousModel = app.modelManager.selected.value
        previousTimeout = app.settingsRepository.modelIdleTimeout.first()
        // These existing idle-policy tests inject a text-only fake backend.
        // Isolate them from the user's independently persisted speaker setting.
        previousDiarization = app.settingsRepository.diarizationEnabled.first()
        app.settingsRepository.setDiarizationEnabled(false)
        app.recognizerManager.forceUnload()
        val model = ModelCatalog.find("parakeet-ultra-q8_0")
        app.recognizerManager.selectModel(model)
        val directory = app.modelManager.directory(model).apply { mkdirs() }
        val target = File(directory, "model.gguf")
        if (!target.exists()) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, "android.permission.READ_EXTERNAL_STORAGE")
            File("/sdcard/Download/parakeet-qa", "${model.id}.gguf").copyTo(target)
        }
        assertTrue(app.modelManager.ensureVerified())
        manager = RecognizerManager(app, app.modelManager, elapsedMillis = { SystemClock.elapsedRealtime() + offset.get() }) { _, _ ->
            FakeBackend(nextPrepare, failDecode).also { backends.add(it) }
        }
        manager.setIdleTimeout(ModelIdleTimeout.TWENTY_MINUTES)
    }

    @After fun restore(): Unit = runBlocking {
        if (::manager.isInitialized) manager.forceUnload()
        if (::previousTimeout.isInitialized) app.settingsRepository.setModelIdleTimeout(previousTimeout)
        if (::previousModel.isInitialized) app.recognizerManager.selectModel(previousModel)
        app.settingsRepository.setDiarizationEnabled(previousDiarization)
    }

    private suspend fun awaitUnload() {
        withTimeout(3000) { while (manager.isReady.value) delay(10) }
        assertTrue(backends.last().closed.await(3, TimeUnit.SECONDS))
        assertNull(manager.failure.value)
    }

    @Test fun idleDeadlineActuallyClosesBackendWithoutWakeRecheck(): Unit = runBlocking {
        assertTrue(manager.initialize())
        // Leave a short real delay; expiry must come from the timer, not another
        // callback. The injected elapsed clock avoids a twenty-minute test.
        offset.addAndGet(1_199_800L)
        manager.recheckIdleTimeout()
        awaitUnload()
    }

    @Test fun immediateWaitsForLastSessionThenReloadsOnDemand(): Unit = runBlocking {
        manager.setIdleTimeout(ModelIdleTimeout.IMMEDIATE)
        val first = manager.createSession()
        val second = manager.createSession()
        try {
            first.finish()
            manager.recheckIdleTimeout()
            assertTrue(manager.isReady.value)
            assertEquals(1L, backends.single().closed.count)
            second.finish()
            awaitUnload()
            val retry = manager.createSession()
            try {
                assertTrue(manager.isReady.value)
                assertEquals(2, backends.size)
            } finally { retry.close(); retry.store.dispose() }
            awaitUnload()
        } finally {
            first.close(); first.store.dispose()
            second.close(); second.store.dispose()
        }
    }

    @Test fun newSessionCancelsOldDeadlineAndCloseRestartsIdle(): Unit = runBlocking {
        assertTrue(manager.initialize())
        offset.addAndGet(1_199_900L)
        manager.recheckIdleTimeout()
        val session = manager.createSession()
        try {
            offset.addAndGet(10_000_000L)
            manager.recheckIdleTimeout()
            delay(200)
            assertTrue(manager.isReady.value)
            assertEquals(1L, backends.single().closed.count)
        } finally { session.close(); session.store.dispose() }
        manager.recheckIdleTimeout()
        assertTrue(manager.isReady.value)
        offset.addAndGet(1_200_000L)
        manager.recheckIdleTimeout()
        awaitUnload()
    }

    @Test fun immediateCannotInterruptPendingModelLoad(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        nextPrepare = { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)) }
        val loading = async(Dispatchers.IO) { manager.createSession() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.setIdleTimeout(ModelIdleTimeout.IMMEDIATE)
            manager.recheckIdleTimeout()
            assertEquals(1L, backends.single().closed.count)
        } finally { proceed.countDown() }
        val session = loading.await()
        try { assertTrue(manager.isReady.value) }
        finally { session.close(); session.store.dispose() }
        awaitUnload()
    }

    @Test fun neverAndPolicyChangesPreserveOriginalIdleStart(): Unit = runBlocking {
        assertTrue(manager.initialize())
        offset.addAndGet(600_000L)
        manager.setIdleTimeout(ModelIdleTimeout.NEVER)
        manager.recheckIdleTimeout()
        assertTrue(manager.isReady.value)
        manager.setIdleTimeout(ModelIdleTimeout.FIVE_MINUTES)
        awaitUnload()
    }

    @Test fun resetInvalidatesOldTimerWithoutUnloadingReplacement(): Unit = runBlocking {
        assertTrue(manager.initialize())
        offset.addAndGet(1_199_900L)
        manager.recheckIdleTimeout()
        manager.forceUnload()
        manager.setIdleTimeout(ModelIdleTimeout.NEVER)
        assertTrue(manager.initialize())
        delay(200)
        assertTrue(manager.isReady.value)
        assertEquals(2, backends.size)
        assertEquals(0L, backends.first().closed.count)
        assertEquals(1L, backends.last().closed.count)
    }

    @Test fun cancellationClosesLastSessionAndAllowsImmediateUnload(): Unit = runBlocking {
        manager.setIdleTimeout(ModelIdleTimeout.IMMEDIATE)
        val ready = CompletableDeferred<Unit>()
        val recording = launch(Dispatchers.IO) {
            val session = manager.createSession()
            try { ready.complete(Unit); awaitCancellation() }
            finally { session.close(); session.store.dispose() }
        }
        ready.await()
        assertTrue(manager.isReady.value)
        recording.cancelAndJoin()
        awaitUnload()
    }

    @Test fun preferenceRoundTripsEveryOption(): Unit = runBlocking {
        for (option in ModelIdleTimeout.entries) {
            app.settingsRepository.setModelIdleTimeout(option)
            assertEquals(option, app.settingsRepository.modelIdleTimeout.first())
        }
    }

    @Test fun failedTranscriptionReleasesMemoryButPreservesError(): Unit = runBlocking {
        failDecode = true
        manager.setIdleTimeout(ModelIdleTimeout.IMMEDIATE)
        val session = manager.createSession()
        try { assertTrue(runCatching { session.accept(ShortArray(192000)) }.isFailure) }
        finally { session.close(); session.store.dispose() }
        assertTrue(backends.single().closed.await(3, TimeUnit.SECONDS))
        assertEquals("Injected decode failure", manager.failure.value)
    }

    @Test fun livePreferenceChangeReleasesRealWorkerAndNextSessionReloads(): Unit = runBlocking {
        val realManager = app.recognizerManager
        val processes = app.getSystemService(android.app.ActivityManager::class.java)
        suspend fun workerPid(): Int = withTimeout(5000) {
            while (true) {
                processes.runningAppProcesses.firstOrNull { it.processName == "${app.packageName}:recognition" }?.let { return@withTimeout it.pid }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") 0
        }
        suspend fun awaitWorkerExit(pid: Int) {
            withTimeout(5000) {
                while (realManager.isReady.value || processes.runningAppProcesses.any { it.pid == pid }) delay(10)
            }
        }
        app.settingsRepository.setModelIdleTimeout(ModelIdleTimeout.NEVER)
        val first = realManager.createSession()
        try {
            val pid = workerPid()
            app.settingsRepository.setModelIdleTimeout(ModelIdleTimeout.IMMEDIATE)
            // Wait for application-scope preference collection while a session
            // still owns the real native worker. It must not be killed mid-use.
            delay(100)
            assertTrue(realManager.isReady.value)
            first.accept(ShortArray(8000))
            first.finish()
            awaitWorkerExit(pid)
            val second = realManager.createSession()
            try {
                val newPid = workerPid()
                assertNotEquals(pid, newPid)
                assertTrue(realManager.isReady.value)
                second.finish()
                awaitWorkerExit(newPid)
            } finally { second.close(); second.store.dispose() }
        } finally { first.close(); first.store.dispose(); realManager.forceUnload() }
    }

    @Test fun reopeningSettingsKeepsFloatingMicrophoneEnabledWithUnloadedModel(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.RECORD_AUDIO")
        assertTrue("Grant the overlay app-op before this test", android.provider.Settings.canDrawOverlays(app))
        val previousService = app.settingsRepository.serviceEnabled.first()
        var activity: android.app.Activity? = null
        try {
            app.recognizerManager.forceUnload()
            app.settingsRepository.setServiceEnabled(true)
            activity = instrumentation.startActivitySync(android.content.Intent(app, io.github.lrq3000.utterlane.settings.SettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            delay(500) // Let onResume's asynchronous DataStore read and service update complete.
            assertFalse(app.recognizerManager.isReady.value)
            assertTrue("Idle unloading must not disable the floating microphone on resume", app.settingsRepository.serviceEnabled.first())
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            app.settingsRepository.setServiceEnabled(previousService)
            if (!previousService) app.stopService(android.content.Intent(app, io.github.lrq3000.utterlane.service.FloatingMicService::class.java))
        }
    }
}
