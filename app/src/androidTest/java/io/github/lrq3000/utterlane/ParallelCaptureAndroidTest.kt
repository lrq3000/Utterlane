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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Run in the isolated .parallelcapture QA identity: no model downloads are needed. */
@RunWith(AndroidJUnit4::class)
class ParallelCaptureAndroidTest {
    @Test fun missingModelDoesNotPreventOrTerminateRecording(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UtterlaneApp
        assertTrue("Use the isolated QA identity", app.packageName.endsWith(".parallelcapture"))
        app.recognizerManager.forceUnload()
        assertFalse("This regression requires an uninstalled model", app.modelManager.isModelReady())
        val capture = ContinuousCapture()
        val complete = CompletableDeferred<SessionFailure?>()
        val closed = CompletableDeferred<Unit>()
        val previousRetention = app.settingsRepository.historyRetention.first()
        app.settingsRepository.setHistoryRetention(HistoryRetention.NONE)
        var recoveryId: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val session = MicrophoneSession(app, scope, onText = { _, _ -> },
            onComplete = { _, failure -> complete.complete(failure) }, recorder = capture,
            onSessionClosed = { closed.complete(Unit) })
        try {
            session.start()
            assertTrue("Model failure prevented microphone startup", capture.started.await(3, TimeUnit.SECONDS))
            withTimeout(3000) { while (capture.frames.get() < 5) delay(10) }
            assertFalse("Model failure completed the session while the user was speaking", complete.isCompleted)
            assertFalse(capture.stopped.get())
            session.stop()
            val failure = withTimeout(5000) { complete.await() }
            recoveryId = failure?.recoveryId
            assertEquals(SessionFailure.Kind.MODEL, failure?.kind)
            assertTrue(capture.frames.get() >= 5)
            val audio = app.microphoneRecordings.get(checkNotNull(recoveryId))
            assertTrue(audio.temporary)
            assertEquals(capture.frames.get() * 320L, audio.samples)
            withTimeout(5000) { closed.await() }
            assertFalse(MicrophoneSession.isBusy())
        } finally {
            session.cancel()
            scope.cancel()
            recoveryId?.let { withContext(Dispatchers.IO) { app.microphoneRecordings.discard(it) } }
            app.settingsRepository.setHistoryRetention(previousRetention)
        }
    }

    @Test fun realMicrophoneCapturesFramesDespiteModelFailure(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        assertTrue(app.packageName.endsWith(".parallelcapture"))
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.RECORD_AUDIO")
        val activity = instrumentation.startActivitySync(
            io.github.lrq3000.utterlane.settings.SettingsActivity.modelSelectionIntent(app)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val previousRetention = app.settingsRepository.historyRetention.first()
        app.settingsRepository.setHistoryRetention(HistoryRetention.NONE)
        val complete = CompletableDeferred<SessionFailure?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val session = MicrophoneSession(app, scope, onText = { _, _ -> }, onComplete = { _, failure -> complete.complete(failure) })
        var recoveryId: String? = null
        try {
            assertFalse(app.modelManager.isModelReady())
            val startedAt = android.os.SystemClock.elapsedRealtime()
            session.start()
            withTimeout(5000) { session.metrics.state.first { it.capturedSamples >= 16000 } }
            assertFalse(complete.isCompleted)
            assertEquals(CapturePhase.CAPTURING, session.metrics.state.value.phase)
            session.stop()
            val failure = withTimeout(5000) { complete.await() }
            recoveryId = failure?.recoveryId
            assertEquals(SessionFailure.Kind.MODEL, failure?.kind)
            val audio = app.microphoneRecordings.get(checkNotNull(recoveryId))
            assertEquals(session.metrics.state.value.capturedSamples, audio.samples)
            android.util.Log.i("ParallelCaptureTest", "Real AudioRecord preserved ${audio.samples} samples despite unavailable model; elapsed=${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        } finally {
            session.cancel(); scope.cancel()
            recoveryId?.let { withContext(Dispatchers.IO) { app.microphoneRecordings.discard(it) } }
            app.settingsRepository.setHistoryRetention(previousRetention)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private class ContinuousCapture : AudioCapture {
        val started = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val frames = AtomicInteger()
        private var observer: CaptureObserver? = null
        override fun setObserver(observer: CaptureObserver) { this.observer = observer }
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            if (stopped.get()) return
            observer?.onStarted()
            started.countDown()
            while (!stopped.get() && shouldContinue()) {
                onSamples(ShortArray(320) { frames.get().toShort() })
                frames.incrementAndGet()
                Thread.sleep(10)
            }
        }
        override fun stop() { stopped.set(true) }
    }
}
