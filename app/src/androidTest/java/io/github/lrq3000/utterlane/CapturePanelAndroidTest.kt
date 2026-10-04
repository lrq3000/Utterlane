package io.github.lrq3000.utterlane

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.CaptureTelemetry
import io.github.lrq3000.utterlane.ui.RecordingPanel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

@RunWith(AndroidJUnit4::class)
class CapturePanelAndroidTest {
    @Test fun realPanelReportsSilenceAndCentralButtonStopsCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var clock = 0L
        val telemetry = CaptureTelemetry { clock }
        var stopped = false
        lateinit var panel: RecordingPanel
        instrumentation.runOnMainSync {
            panel = RecordingPanel(app, { stopped = true; telemetry.stopping(); telemetry.captureEnded() }, {})
            panel.bind(scope, telemetry.state)
        }
        try {
            telemetry.started(); clock = 2000; telemetry.samples(ShortArray(800), true)
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
            telemetry.completed(null)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(texts(panel).any { it.text.toString() == "100%" })
                assertFalse("Completed work must release the screen", panel.keepScreenOn)
            }
        } finally { instrumentation.runOnMainSync { panel.release() }; scope.cancel() }
    }
    private fun texts(view: View): List<TextView> {
        if (view is TextView) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    }
}
