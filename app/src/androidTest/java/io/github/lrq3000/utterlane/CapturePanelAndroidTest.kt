package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.CaptureMetrics
import io.github.lrq3000.utterlane.asr.RecognitionActivity
import io.github.lrq3000.utterlane.ui.RecordingPanel
import io.github.lrq3000.utterlane.ui.RecognitionStatusText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

@RunWith(AndroidJUnit4::class)
class CapturePanelAndroidTest {
    @Test fun loadingAndRecognitionFailureKeepTheMicrophoneControlsLive(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val previous = app.settingsRepository.showTranscriptionStreamStatistics.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val metrics = CaptureMetrics()
        lateinit var panel: RecordingPanel
        instrumentation.runOnMainSync { panel = RecordingPanel(app, {}, {}) }
        try {
            app.settingsRepository.setShowTranscriptionStreamStatistics(false)
            instrumentation.runOnMainSync { panel.bind(scope, metrics.state) }
            metrics.preparingModel(); metrics.started(); metrics.samples(shortArrayOf(5000), true)
            awaitPanel { texts(panel).any { it.visibility == View.VISIBLE && it.text.toString() == app.getString(R.string.model_loading) } }
            instrumentation.runOnMainSync {
                assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.recording_done).visibility)
                assertTrue(panel.findViewById<View>(R.id.recording_done).isEnabled)
                assertTrue(texts(panel).any { it.text.toString() == app.getString(R.string.capture_listening) })
            }
            metrics.recognitionFailed("injected load failure")
            awaitPanel { texts(panel).any { it.text.toString() == app.getString(R.string.capture_recording_without_transcription) } }
            instrumentation.runOnMainSync { assertTrue(panel.findViewById<View>(R.id.recording_done).isEnabled) }
        } finally {
            instrumentation.runOnMainSync { panel.release() }; scope.cancel()
            app.settingsRepository.setShowTranscriptionStreamStatistics(previous)
        }
    }

    @Test fun initialPanelDoesNotShowStreamStatistics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        instrumentation.runOnMainSync {
            val panel = RecordingPanel(app, {}, {})
            try {
                val backlog = app.getString(R.string.recognition_backlog, 0.0, 0.0, 0.0)
                assertFalse("Statistics must be hidden before an explicit opt-in",
                    texts(panel).any { it.visibility == View.VISIBLE && backlog in it.text.toString() })
            } finally { panel.release() }
        }
    }

    @Test fun realPanelReportsSilenceAndCentralButtonStopsCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var clock = 0L
        val metrics = CaptureMetrics { clock }
        var stopped = false
        lateinit var panel: RecordingPanel
        instrumentation.runOnMainSync {
            panel = RecordingPanel(app, { stopped = true; metrics.stopping(); metrics.captureEnded() }, {})
            panel.bind(scope, metrics.state)
        }
        try {
            metrics.started(); clock = 2000; metrics.samples(ShortArray(800), true)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("Active capture must prevent screen timeout", panel.keepScreenOn)
                assertTrue(texts(panel).any { it.text.toString() == app.getString(R.string.capture_no_signal) && it.visibility == View.VISIBLE })
                val button = panel.findViewById<android.widget.Button>(R.id.recording_done)
                assertTrue(button.isEnabled)
                assertTrue(button.layoutParams.height >= 130 * app.resources.displayMetrics.density)
                button.performClick()
            }
            assertTrue(stopped)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(View.GONE, panel.findViewById<View>(R.id.recording_done).visibility)
                assertTrue("Draining audio must still prevent timeout", panel.keepScreenOn)
            }
            metrics.completed(null)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(texts(panel).any { it.text.toString() == "100%" })
                assertFalse("Completed work must release the screen", panel.keepScreenOn)
            }
        } finally { instrumentation.runOnMainSync { panel.release() }; scope.cancel() }
    }

    @Test fun statisticsToggleRedrawsWithoutNewAudioAndPreservesWaveformAndProgress() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val settings = app.settingsRepository
        val previous = settings.showTranscriptionStreamStatistics.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val metrics = CaptureMetrics()
        metrics.model("QA stream")
        metrics.captured(32000)
        metrics.recognition(RecognitionActivity(stage = "speaker/transformer", elapsedMillis = 10000,
            sinceProgressMillis = 2000, active = true))
        val backlog = RecognitionStatusText.backlog(app, metrics.state.value)
        val activity = RecognitionStatusText.activity(app, metrics.state.value.recognition)
        lateinit var panel: RecordingPanel
        instrumentation.runOnMainSync { panel = RecordingPanel(app, {}, {}) }
        try {
            settings.setShowTranscriptionStreamStatistics(false)
            instrumentation.runOnMainSync { panel.bind(scope, metrics.state) }
            awaitPanel { texts(panel).any { it.text.toString() == "QA stream" } }
            instrumentation.runOnMainSync {
                assertFalse(texts(panel).any { it.visibility == View.VISIBLE && backlog in it.text.toString() })
                assertFalse(texts(panel).any { it.visibility == View.VISIBLE && it.text.toString() == activity })
                assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.recording_done).visibility)
            }
            // Do not publish another capture snapshot: settings changes alone must redraw.
            settings.setShowTranscriptionStreamStatistics(true)
            awaitPanel { texts(panel).any { it.visibility == View.VISIBLE && it.text.toString() == activity } }
            instrumentation.runOnMainSync {
                assertTrue(texts(panel).any { it.visibility == View.VISIBLE && backlog in it.text.toString() })
                assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.recording_done).visibility)
            }
            settings.setShowTranscriptionStreamStatistics(false)
            awaitPanel { texts(panel).any { it.text.toString() == "QA stream" } }
            metrics.captureEnded()
            metrics.processed(16000, 1000)
            awaitPanel { texts(panel).any { it.text.toString().startsWith("50%") } }
            instrumentation.runOnMainSync {
                assertFalse(texts(panel).any { it.visibility == View.VISIBLE && it.text.toString() == activity })
                assertEquals(View.GONE, panel.findViewById<View>(R.id.recording_done).visibility)
                assertTrue(panel.keepScreenOn)
            }
        } finally {
            instrumentation.runOnMainSync { panel.release() }
            scope.cancel()
            settings.setShowTranscriptionStreamStatistics(previous)
        }
    }

    private suspend fun awaitPanel(condition: () -> Boolean) = withTimeout(5000) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) break
            delay(20)
        }
    }

    private fun texts(view: View): List<TextView> {
        if (view is TextView) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    }
}
