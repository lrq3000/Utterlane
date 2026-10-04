package io.github.lrq3000.utterlane

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.TranscriptionPower
import io.github.lrq3000.utterlane.asr.AudioRecorder
import io.github.lrq3000.utterlane.asr.ModelCatalog
import io.github.lrq3000.utterlane.asr.WorkerRecognitionBackend
import io.github.lrq3000.utterlane.asr.TranscriptStore
import io.github.lrq3000.utterlane.service.StreamingTextTarget
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android lock ownership; no model or microphone permission is needed. */
@RunWith(AndroidJUnit4::class)
class TranscriptionPowerAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun cancellingWhileScreenOffInterruptsPendingWorkerWait() = runBlocking {
        val backend = WorkerRecognitionBackend(instrumentation.targetContext, ModelCatalog.DEFAULT)
        val wait = WorkerRecognitionBackend::class.java.getDeclaredMethod("await", CompletableFuture::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
        val entered = CountDownLatch(1)
        shell("input keyevent 223")
        val job = launch(Dispatchers.IO) {
            runInterruptible {
                entered.countDown()
                try { wait.invoke(backend, CompletableFuture<android.os.Bundle>(), 90L) }
                catch (error: InvocationTargetException) { throw error.cause!! }
            }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            // The real CompletableFuture wait is sleep-paused, but cancellation
            // must release it independently of any wake event or worker reply.
            delay(100)
            job.cancel()
            withTimeout(3000) { job.join() }
            assertTrue("Cancelling one waiter must not kill other sessions' shared backend", backend.isAvailable())
        } finally {
            backend.close()
            job.cancelAndJoin()
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
        }
    }

    @Test fun cancelledTargetCannotInsertAfterPendingDiskRead() = runBlocking {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        val context = instrumentation.targetContext
        val store = TranscriptStore(File.createTempFile("cancel-delivery", ".txt", context.cacheDir))
        store.append("must remain recoverable")
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val blocker = executor.submit { synchronized(store) { held.countDown(); release.await(5, TimeUnit.SECONDS) } }
        var insertions = 0
        val target = StreamingTextTarget(context) { insertions++; true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var delivery: Job
        try {
            assertTrue(held.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                delivery = scope.launch { target.accept(store) }
                target.close()
            }
            release.countDown()
            withTimeout(3000) { delivery.join() }
            assertEquals(0, insertions)
            assertEquals("must remain recoverable", store.file.readText())
        } finally {
            release.countDown(); blocker.get(3, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { target.close() }
            scope.cancel(); executor.shutdownNow(); store.dispose()
        }
    }

    @Test fun realMicrophoneCanStopAfterScreenOffAndWake() {
        val context = instrumentation.targetContext
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        val recorder = AudioRecorder()
        val frames = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val power = TranscriptionPower(context) { recorder.resumeAfterSleep() }
        val capture = executor.submit { recorder.startRecording({ frames.countDown() }, { true }) }
        try {
            assertTrue("Real AudioRecord should supply PCM", frames.await(5, TimeUnit.SECONDS))
            shell("input keyevent 223")
            Thread.sleep(500)
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            recorder.stop()
            capture.get(3, TimeUnit.SECONDS)
            assertFalse(recorder.isRecording())
        } finally {
            recorder.stop()
            power.close()
            executor.shutdownNow()
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
        }
    }

    @Test fun overlappingSessionsRetainPowerUntilBothFinish() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        val first = TranscriptionPower(instrumentation.targetContext)
        val second = TranscriptionPower(instrumentation.targetContext)
        try {
            assertTrue(locks().contains("Utterlane:transcription-cpu"))
            assertTrue(locks().contains("Utterlane:transcription-screen"))
            first.close()
            assertTrue(locks().contains("Utterlane:transcription-cpu"))
        } finally { first.close(); second.close() }
        assertFalse(locks().contains("Utterlane:transcription-"))
    }

    private fun locks(): String = shell("dumpsys power").substringAfter("Wake Locks: ").substringBefore("Suspend Blockers:")
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
}
