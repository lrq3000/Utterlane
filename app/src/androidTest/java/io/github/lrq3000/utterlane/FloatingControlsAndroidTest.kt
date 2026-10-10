package io.github.lrq3000.utterlane

import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.lrq3000.utterlane.asr.AudioCapture
import io.github.lrq3000.utterlane.asr.AudioRecorder
import io.github.lrq3000.utterlane.asr.CaptureObserver
import io.github.lrq3000.utterlane.asr.MicrophoneSession
import io.github.lrq3000.utterlane.asr.MicrophoneSessionFactory
import io.github.lrq3000.utterlane.history.RecordingRecovery
import io.github.lrq3000.utterlane.service.FloatingMicService
import io.github.lrq3000.utterlane.settings.RuntimeOptions
import io.github.lrq3000.utterlane.settings.SettingsActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Run serially with a clean .floatingports QA identity, no installed model, and a working mic. */
@RunWith(AndroidJUnit4::class)
class FloatingControlsAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UtterlaneApp
    private val ui = OnboardingTestUi()

    @Test fun pinchResizeOutsideSettingsKeepsOneRealCaptureAndRecoversAllAudioWithoutAModel() = runBlocking {
        assertTrue("Use an isolated floating/combined ports QA application",
            app.packageName.endsWith(".floatingports") || app.packageName.endsWith(".audiorecorderports"))
        assertFalse("This test proves capture without installed recognition", app.modelManager.isModelReady())
        assertFalse("Start with the floating service disabled", app.settingsRepository.serviceEnabled.first())
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.RECORD_AUDIO")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        ui.prepare()

        val previousFactory = app.microphoneSessions
        val previousSize = app.settingsRepository.floatingButtonSizeDp.first()
        val previousPosition = app.settingsRepository.buttonPosition.first()
        val previousHistory = app.settingsRepository.audioHistoryEnabled.first()
        val before = app.recordingHistory.list().map { it.id }.toSet()
        val creations = AtomicInteger()
        val capture = ObservedAudioRecord()
        try {
            // Rotate during capture below to test the display-bound size clamp.
            @Suppress("DEPRECATION")
            val display = app.getSystemService(android.view.WindowManager::class.java).defaultDisplay
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val portraitRotation = (display.rotation + if (metrics.widthPixels > metrics.heightPixels) 1 else 0) % 4
            instrumentation.uiAutomation.setRotation(portraitRotation)
            app.settingsRepository.setAudioHistoryEnabled(false)
            app.settingsRepository.setFloatingButtonSizeDp(56)
            app.settingsRepository.setButtonPosition(100, 300)
            app.microphoneSessions = MicrophoneSessionFactory { creations.incrementAndGet(); capture }
            val activity = instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java)
                .putExtra(RecordingRecovery.EXTRA_MODELS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            val modelDialog = ui.textNode(app.getString(R.string.model_choose))
            @Suppress("DEPRECATION") modelDialog.recycle()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.clickDescription(app.getString(R.string.floating_enable))
            await { app.settingsRepository.serviceEnabled.first() }
            // Settings are only needed for initial enablement. Resize directly on
            // the floating mic while the user is outside Utterlane's Settings.
            instrumentation.runOnMainSync { activity.finish() }
            await { activity.isDestroyed }
            // An overlay appears asynchronously, outside the settings scroll view.
            // Waiting for it must not scroll the underlying app.
            ui.click(buttonId(), scroll = false)
            await { capture.samples.get() >= 16000 }

            pinch(buttonBounds(), 1.5f)
            await { app.settingsRepository.floatingButtonSizeDp.first() == 84 }
            assertContinues(capture, creations)
            await { kotlin.math.abs(buttonBounds().width() - 84 * app.resources.displayMetrics.density) <= 1 }

            pinch(buttonBounds(), 0.75f)
            await { app.settingsRepository.floatingButtonSizeDp.first() == 63 }
            await { kotlin.math.abs(buttonBounds().width() - 63 * app.resources.displayMetrics.density) <= 1 }
            assertContinues(capture, creations)

            dragOutAndBack(buttonBounds())
            assertContinues(capture, creations)
            ui.screenshot("floating-custom-recording")
            instrumentation.uiAutomation.setRotation((portraitRotation + 1) % 4)
            await {
                val bounds = buttonBounds()
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                app.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= metrics.widthPixels && bounds.bottom <= metrics.heightPixels
            }
            assertContinues(capture, creations)
            assertTrue(app.settingsRepository.serviceEnabled.first())

            ui.click(buttonId(), scroll = false)
            await { !capture.running.get() && !MicrophoneSession.isBusy() }
            val saved = app.recordingHistory.list().single { it.id !in before }
            assertEquals("Resize/rotation must preserve every delivered PCM sample", capture.samples.get(), saved.samples)
            assertTrue("Model failure leaves useful audio for recovery", saved.samples > 16000)
            // Wait for the service's posted recovery UI before cleaning up. Busy
            // admission is released before that Main-thread callback is delivered.
            @Suppress("DEPRECATION")
            ui.node("dialog_title").recycle()
        } finally {
            capture.stop()
            app.stopService(Intent(app, FloatingMicService::class.java))
            await { !MicrophoneSession.isBusy() }
            app.microphoneSessions = previousFactory
            instrumentation.runOnMainSync {
                // Finish the live recovery/setup screens, including any instances
                // replaced by a configuration change before an early test failure.
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED)
                    .flatMap { monitor.getActivitiesInStage(it) }.toSet()
                    .filter { it is SettingsActivity || it is io.github.lrq3000.utterlane.transcribe.TranscribeActivity }
                    .forEach { it.finish() }
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            app.settingsRepository.setServiceEnabled(false)
            app.settingsRepository.setFloatingButtonSizeDp(previousSize)
            app.settingsRepository.setButtonPosition(previousPosition.first, previousPosition.second)
            app.settingsRepository.setAudioHistoryEnabled(previousHistory)
            app.recordingHistory.list().filter { it.id !in before }.forEach {
                app.recordingHistory.delete(it.id)
                RecordingRecovery.dismissNotification(app, it.id)
            }
        }
    }

    private fun buttonId() = app.resources.getResourceName(R.id.floating_mic_button)

    private fun buttonBounds(): Rect {
        val node = ui.node(buttonId())
        return try { Rect().also(node::getBoundsInScreen) } finally { @Suppress("DEPRECATION") node.recycle() }
    }

    private suspend fun assertContinues(capture: ObservedAudioRecord, creations: AtomicInteger) {
        val before = capture.samples.get()
        await { capture.samples.get() >= before + 3200 }
        assertTrue(capture.running.get())
        assertEquals("Presentation must retain the original capture", 1, creations.get())
        assertEquals(0, capture.stops.get())
    }

    private fun pinch(bounds: Rect, scale: Float) {
        val time = SystemClock.uptimeMillis()
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val initial = bounds.width() * 0.15f
        send(time, MotionEvent.ACTION_DOWN, listOf(x - initial to y))
        send(time, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x - initial to y, x + initial to y))
        val expanded = initial * scale
        send(time, MotionEvent.ACTION_MOVE, listOf(x - expanded to y, x + expanded to y))
        send(time, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x - expanded to y, x + expanded to y))
        send(time, MotionEvent.ACTION_UP, listOf(x - expanded to y))
    }

    private fun dragOutAndBack(bounds: Rect) {
        val time = SystemClock.uptimeMillis()
        val start = bounds.exactCenterX() to bounds.exactCenterY()
        send(time, MotionEvent.ACTION_DOWN, listOf(start))
        send(time, MotionEvent.ACTION_MOVE, listOf(start.first + 100 to start.second))
        send(time, MotionEvent.ACTION_MOVE, listOf(start))
        send(time, MotionEvent.ACTION_UP, listOf(start))
    }

    private fun send(downTime: Long, action: Int, positions: List<Pair<Float, Float>>) {
        val properties = Array(positions.size) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = positions.map { (x, y) -> MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, positions.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(15000) { while (!condition()) delay(50) }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    /** Observation only: the production AudioRecord still opens, reads and releases the microphone. */
    private class ObservedAudioRecord : AudioCapture {
        private val recorder = AudioRecorder()
        val samples = AtomicLong()
        val running = AtomicBoolean()
        val stops = AtomicInteger()
        override fun startRecording(onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) =
            startRecording(RuntimeOptions(), onSamples, shouldContinue)
        override fun startRecording(options: RuntimeOptions, onSamples: (ShortArray) -> Unit, shouldContinue: () -> Boolean) {
            running.set(true)
            try {
                recorder.startRecording(options, { block -> onSamples(block); samples.addAndGet(block.size.toLong()) }, shouldContinue)
            } finally { running.set(false) }
        }
        override fun setObserver(observer: CaptureObserver) = recorder.setObserver(observer)
        override fun configureMicrophone(options: io.github.lrq3000.utterlane.audio.MicrophoneOptions) = recorder.configureMicrophone(options)
        override fun resumeAfterSleep() = recorder.resumeAfterSleep()
        override fun stop() { stops.incrementAndGet(); recorder.stop() }
    }
}
