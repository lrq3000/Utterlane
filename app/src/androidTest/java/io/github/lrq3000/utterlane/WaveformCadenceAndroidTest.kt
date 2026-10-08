package io.github.lrq3000.utterlane

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.asr.CaptureMetrics
import io.github.lrq3000.utterlane.history.HistoryActivity
import io.github.lrq3000.utterlane.ui.RecordingPanel
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Exercise the real native view with controlled PCM, without a model or live mic.
 * Pixel positions establish scrolling cadence; a static screenshot alone cannot.
 * The historical per-callback contract moves the marker one column per callback. */
@RunWith(AndroidJUnit4::class)
class WaveformCadenceAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun markerScrollsAtCaptureCadenceWithThirtyHzPublication() = verify(30)
    @Test fun markerScrollsAtCaptureCadenceWithSixtyHzPublication() = verify(60)
    @Test fun markerScrollsAtCaptureCadenceWithTwoHundredHzPublication() = verify(200)

    private fun verify(rate: Int) = runBlocking {
        val context = instrumentation.targetContext
        // History provides a normal app window without first-run onboarding.
        // Replace only this test-owned activity's content with the production panel.
        val activity = instrumentation.startActivitySync(HistoryActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var now = 0L
        val metrics = CaptureMetrics { now }
        metrics.setVisualRefreshRate(rate)
        metrics.model("Waveform QA: $rate Hz, 10 ms PCM blocks")
        var panel: RecordingPanel? = null
        lateinit var button: View
        try {
            instrumentation.runOnMainSync {
                val recordingPanel = RecordingPanel(activity, {}, {})
                panel = recordingPanel
                recordingPanel.bind(scope, metrics.state)
                button = recordingPanel.findViewById(R.id.recording_done)
                val container = FrameLayout(activity)
                container.addView(recordingPanel, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
                activity.setContentView(container)
            }
            // One tall bar followed by short bars makes the position of this
            // particular callback measurable after multiple refresh deadlines.
            metrics.samples(ShortArray(160) { 16000 }, true)
            awaitMarker(button, 63)
            repeat(65) { index ->
                delay(10)
                now += 10
                metrics.samples(ShortArray(160) { 128 }, true)
                val callbacks = index + 1
                if (callbacks == 20 || callbacks == 50) {
                    awaitMarker(button, 63 - callbacks)
                    OnboardingTestUi().screenshot("waveform-$rate-hz-${now}ms")
                }
            }
            // Flush the pending frame, not another input point. After 65 newer
            // callbacks the marker must be gone rather than replayed as backlog.
            now += 100; metrics.tick()
            awaitMarker(button, null)
        } finally {
            instrumentation.runOnMainSync {
                panel?.release()
                activity.finish()
            }
            scope.cancel()
        }
    }

    private suspend fun awaitMarker(button: View, expectedIndex: Int?) {
        var actual: Float? = null
        var expected: Float? = null
        try {
            withTimeout(5000) {
                while (true) {
                    var matched = false
                    instrumentation.runOnMainSync {
                        if (!button.isShown || button.width == 0 || button.height == 0) return@runOnMainSync
                        val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
                        try {
                            button.draw(Canvas(bitmap))
                            // Above the low bars, below the tall bar's tip, away
                            // from the button caption. Inspect only the bar region.
                            val padding = (24 * button.resources.displayMetrics.density).toInt()
                            val y = (button.height * 0.18f).toInt()
                            var totalX = 0L
                            var pixels = 0
                            for (x in padding until button.width - padding) {
                                val color = bitmap.getPixel(x, y)
                                if (Color.red(color) > 245 && Color.green(color) > 245 && Color.blue(color) > 245) {
                                    totalX += x; pixels++
                                }
                            }
                            actual = if (pixels == 0) null else totalX.toFloat() / pixels
                            expected = expectedIndex?.let { padding + it * (button.width - 2f * padding) / 64 }
                            matched = if (expected == null) actual == null
                                else actual?.let { abs(it - expected!!) <= 2f } == true
                        } finally { bitmap.recycle() }
                    }
                    if (matched) break
                    delay(10)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Waveform marker: expected column $expectedIndex at x=$expected, rendered x=$actual", e)
        }
    }
}
