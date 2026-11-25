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
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import io.github.lrq3000.utterlane.history.HistoryActivity

@RunWith(AndroidJUnit4::class)
class CapturePanelAndroidTest {
    @Test fun speakerGlyphIsVisibleInTheRenderedLivePanelInBothStates() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val settings = app.settingsRepository
        val previousTheme = settings.themeMode.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val session = io.github.lrq3000.utterlane.asr.MicrophoneSession(app, scope, { _, _ -> }, { _, _ -> })
        val control = session.speakerControl.apply { set(false) }
        val overlayOp = "${app.packageName} SYSTEM_ALERT_WINDOW"
        fun appOp(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("appops $command")).use { it.readBytes().decodeToString() }
        val priorOverlayMode = Regex("""SYSTEM_ALERT_WINDOW:\s*(\w+)""").find(appOp("get $overlayOp"))?.groupValues?.get(1) ?: "default"
        val grantOverlay = !Settings.canDrawOverlays(app)
        org.junit.Assume.assumeTrue("Only grant overlay permission for this test's isolated application ID",
            !grantOverlay || app.packageName.endsWith(".qolsandbox"))
        val activity = instrumentation.startActivitySync(HistoryActivity.intent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val context = ContextThemeWrapper(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val overlay = io.github.lrq3000.utterlane.ui.RecordingOverlay(context)
        lateinit var button: android.widget.ToggleButton
        try {
            if (grantOverlay) appOp("set $overlayOp allow")
            awaitPanel { Settings.canDrawOverlays(app) }
            settings.setThemeMode("dark")
            instrumentation.runOnMainSync {
                overlay.show()
                overlay.bind(scope, session)
                val panel = overlay.javaClass.getDeclaredField("overlayView").apply { isAccessible = true }.get(overlay) as View
                button = panel.findViewById(R.id.recording_speakers)
            }
            awaitPanel { button.isAttachedToWindow && button.visibility == View.GONE }
            settings.setThemeMode("light")
            awaitPanel {
                (button.rootView.background as? android.graphics.drawable.GradientDrawable)
                    ?.color?.defaultColor == Color.WHITE
            }
            session.metrics.started()
            awaitPanel { button.isShown && button.width > 0 }
            // The label is part of this control's content, not an optional foreground decoration.
            // Suppress the foreground slot to verify the icon is independent of native overlay/theme rendering.
            instrumentation.runOnMainSync { button.foreground = null }
            val palette = io.github.lrq3000.utterlane.ui.theme.BlueHarmony.Light
            for (enabled in listOf(false, true)) {
                control.set(enabled)
                awaitPanel { button.isChecked == enabled }
                // The first callback precedes drawing; wait through that frame before reading the compositor.
                val frame = CompletableDeferred<Unit>()
                instrumentation.runOnMainSync { button.postOnAnimation { button.postOnAnimation { frame.complete(Unit) } } }
                withTimeout(5000) { frame.await() }
                val bounds = Rect()
                instrumentation.runOnMainSync {
                    val (x, y) = IntArray(2).also(button::getLocationOnScreen)
                    bounds.set(x, y, x + button.width, y + button.height)
                }
                val expected = if (enabled) palette.primary.toArgb() else null
                var visibleGlyphPixels = 0
                val deadline = SystemClock.uptimeMillis() + 3000
                do {
                    instrumentation.waitForIdleSync()
                    val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        visibleGlyphPixels = glyphPixels(screen, bounds, expected)
                    } finally { screen.recycle() }
                    if (visibleGlyphPixels >= 5) break
                    delay(50)
                } while (SystemClock.uptimeMillis() < deadline)
                assertTrue("Speaker glyph missing from the composited live panel (enabled=$enabled, glyphPixels=$visibleGlyphPixels)", visibleGlyphPixels >= 5)
            }
        } finally {
            try { instrumentation.runOnMainSync { overlay.hide(); activity.finish() } }
            finally {
                scope.cancel()
                settings.setThemeMode(previousTheme)
                if (grantOverlay) appOp("set $overlayOp $priorOverlayMode")
            }
        }
    }

    private fun glyphPixels(image: Bitmap, bounds: Rect, color: Int?): Int {
        // Inspect the central 24 dp glyph, excluding the outline of the larger 40 dp checked fill.
        val inset = bounds.width() / 4
        var count = 0
        for (y in bounds.top + inset until bounds.bottom - inset) {
            for (x in bounds.left + inset until bounds.right - inset) {
                val pixel = image.getPixel(x, y)
                if (color != null && pixel == color || color == null && Color.red(pixel) >= 190 &&
                    Color.green(pixel) >= 190 && Color.blue(pixel) > Color.red(pixel) + 10) count++
            }
        }
        return count
    }

    @Test fun speakerSettingsShortcutExplicitlyFinishesBeforeItCanNavigate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val metrics = CaptureMetrics().apply { started(); speakerModelNeeded() }
        val control = io.github.lrq3000.utterlane.asr.LiveSpeakerControl()
        var stops = 0
        lateinit var panel: RecordingPanel
        try {
            instrumentation.runOnMainSync {
                panel = RecordingPanel(app, { stops++; metrics.captureEnded() }, {}).also {
                    it.bind(scope, metrics.state, control)
                }
            }
            awaitPanel { panel.findViewById<View>(R.id.recording_speaker_settings).visibility == View.VISIBLE }
            instrumentation.runOnMainSync {
                val action = panel.findViewById<TextView>(R.id.recording_speaker_settings)
                assertEquals(0, stops)
                assertEquals(app.getString(R.string.finish_and_open_speaker_settings), action.text)
                assertTrue(action.performClick())
                assertEquals(1, stops)
                assertTrue(control.settingsRequested)
                assertFalse(action.isEnabled)
                assertTrue(control.takeSettingsRequest())
                assertFalse("A completed request cannot be consumed again", control.takeSettingsRequest())
            }
        } finally { instrumentation.runOnMainSync { panel.release() }; scope.cancel() }
    }

    @Test fun speakerToggleFitsInsideTheWaveformAndDoesNotStopRecording() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val metrics = CaptureMetrics().apply { started() }
        val control = io.github.lrq3000.utterlane.asr.LiveSpeakerControl()
        var stops = 0
        lateinit var without: RecordingPanel
        lateinit var with: RecordingPanel
        val activity = instrumentation.startActivitySync(HistoryActivity.intent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                without = RecordingPanel(activity, {}, {}).also { it.bind(scope, metrics.state) }
                with = RecordingPanel(activity, { stops++ }, {}).also { it.bind(scope, metrics.state, control) }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(with, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                })
            }
            awaitPanel { with.findViewById<View>(R.id.recording_speakers).visibility == View.VISIBLE &&
                without.findViewById<View>(R.id.recording_done).visibility == View.VISIBLE }
            instrumentation.runOnMainSync {
                val width = (360 * app.resources.displayMetrics.density).toInt()
                for (panel in listOf(without, with)) {
                    panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    panel.layout(0, 0, width, panel.measuredHeight)
                }
                assertEquals("Speaker control must not add height", without.height, with.height)
                val button = with.findViewById<android.widget.ToggleButton>(R.id.recording_speakers)
                val finish = with.findViewById<View>(R.id.recording_done)
                val buttonBounds = Rect(0, 0, button.width, button.height).also { with.offsetDescendantRectToMyCoords(button, it) }
                val finishBounds = Rect(0, 0, finish.width, finish.height).also { with.offsetDescendantRectToMyCoords(finish, it) }
                assertTrue("Control belongs inside the waveform", finishBounds.contains(buttonBounds))
                val density = app.resources.displayMetrics.density
                assertEquals("Home's trailing inset", 9f, (finishBounds.right - buttonBounds.right) / density, 1f)
                assertEquals("Home's top inset", 8f, (buttonBounds.top - finishBounds.top) / density, 1f)
                assertTrue("Speaker touch target must remain at least 48 dp", buttonBounds.width() >= (48 * density).toInt())
                assertTrue(button.tooltipText?.isNotBlank() == true)
                assertTrue("The icon must not carry a visible text label", button.text.isNullOrEmpty())
                tap(with, buttonBounds)
            }
            awaitPanel { control.state.value == true }
            instrumentation.runOnMainSync {
                assertEquals("Tapping the speaker toggle must not finish the recording", 0, stops)
                val finish = with.findViewById<View>(R.id.recording_done)
                val finishBounds = Rect(0, 0, finish.width, finish.height).also { with.offsetDescendantRectToMyCoords(finish, it) }
                tap(with, finishBounds)
            }
            awaitPanel { stops == 1 }
            instrumentation.runOnMainSync {
                // App-language changes recreate the panel. Start with a new layout so inherited directions and relative margins resolve together.
                with.release()
                with = RecordingPanel(activity, { stops++ }, {}).also { it.bind(scope, metrics.state, control) }
                activity.setContentView(FrameLayout(activity).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    addView(with, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                })
            }
            awaitPanel { with.findViewById<View>(R.id.recording_speakers).visibility == View.VISIBLE }
            instrumentation.runOnMainSync {
                val width = (360 * app.resources.displayMetrics.density).toInt()
                with.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                with.layout(0, 0, width, with.measuredHeight)
                val button = with.findViewById<View>(R.id.recording_speakers)
                val finish = with.findViewById<View>(R.id.recording_done)
                val buttonBounds = Rect(0, 0, button.width, button.height).also { with.offsetDescendantRectToMyCoords(button, it) }
                val finishBounds = Rect(0, 0, finish.width, finish.height).also { with.offsetDescendantRectToMyCoords(finish, it) }
                assertTrue("RTL control is still inside the waveform", finishBounds.contains(buttonBounds))
                assertEquals("RTL mirrors the trailing inset (waveform container direction=${(finish.parent as View).layoutDirection})", 9f,
                    (buttonBounds.left - finishBounds.left) / app.resources.displayMetrics.density, 1f)
                tap(with, buttonBounds)
            }
            awaitPanel { control.state.value == false }
            instrumentation.runOnMainSync { assertEquals("RTL speaker tap must not finish the recording", 1, stops) }
        } finally {
            instrumentation.runOnMainSync { without.release(); with.release(); activity.finish() }
            scope.cancel()
        }
    }

    @Test fun speakerToggleUsesHomesTransparentAndRoundCheckedBackgroundInBothThemes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val settings = app.settingsRepository
        val previous = settings.themeMode.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val metrics = CaptureMetrics().apply { started() }
        val control = io.github.lrq3000.utterlane.asr.LiveSpeakerControl()
        lateinit var panel: RecordingPanel
        lateinit var button: android.widget.ToggleButton
        try {
            instrumentation.runOnMainSync {
                panel = RecordingPanel(app, {}, {}).also { it.bind(scope, metrics.state, control) }
                button = panel.findViewById(R.id.recording_speakers)
            }
            fun renderedBackground(fractionX: Float, fractionY: Float): Int {
                val size = (48 * app.resources.displayMetrics.density).toInt()
                val drawable = button.background
                drawable.setBounds(0, 0, size, size)
                val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    drawable.draw(android.graphics.Canvas(bitmap))
                    return bitmap.getPixel((size * fractionX).toInt(), (size * fractionY).toInt())
                } finally { bitmap.recycle() }
            }
            for (dark in listOf(false, true)) {
                settings.setThemeMode(if (dark) "dark" else "light")
                val palette = io.github.lrq3000.utterlane.ui.theme.BlueHarmony.palette(dark)
                control.set(false)
                awaitPanel { !button.isChecked }
                instrumentation.runOnMainSync {
                    assertEquals("Off must show the waveform gradient through the button",
                        Color.TRANSPARENT, renderedBackground(.5f, .5f))
                }
                control.set(true)
                awaitPanel { button.isChecked && renderedBackground(.5f, .5f) == palette.container.toArgb() }
                instrumentation.runOnMainSync {
                    assertEquals("The checked fill must be round, not squared off",
                        Color.TRANSPARENT, renderedBackground(.16f, .16f))
                    assertEquals("Checked fill uses the same color as Home",
                        palette.container.toArgb(), renderedBackground(.5f, .23f))
                }
            }
        } finally {
            instrumentation.runOnMainSync { panel.release() }
            scope.cancel()
            settings.setThemeMode(previous)
        }
    }

    @Test fun actualInputAndRedWarningSurvivePanelRecreationWithStatisticsOff() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UtterlaneApp
        val previous = app.settingsRepository.showTranscriptionStreamStatistics.first()
        val previousTheme = app.settingsRepository.themeMode.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val metrics = CaptureMetrics { 0L }
        val input = io.github.lrq3000.utterlane.audio.CaptureInputState(
            actual = io.github.lrq3000.utterlane.audio.AudioInput("phone", "", false),
            fallbackFrom = io.github.lrq3000.utterlane.audio.AudioInput("headset", "Headset", true),
            fallbackReason = io.github.lrq3000.utterlane.audio.InputFallbackReason.DISCONNECTED,
            receivingFallback = true)
        metrics.started(); metrics.input(input)
        var panel: RecordingPanel? = null
        try {
            app.settingsRepository.setShowTranscriptionStreamStatistics(false)
            repeat(2) {
                val dark = it == 1
                app.settingsRepository.setThemeMode(if (dark) "dark" else "light")
                instrumentation.runOnMainSync {
                    panel?.release()
                    panel = RecordingPanel(app, {}, {}).also { it.bind(scope, metrics.state) }
                    assertNotNull("Always-visible actual-input caption", panel!!.findViewById<TextView>(R.id.recording_input))
                    assertNotNull("Separate persistent warning", panel!!.findViewById<TextView>(R.id.recording_input_warning))
                }
                val caption = app.getString(R.string.audio_input_caption, app.getString(R.string.audio_input_phone))
                awaitPanel { panel!!.findViewById<TextView>(R.id.recording_input).text.toString() == caption }
                val warningColor = (if (dark) io.github.lrq3000.utterlane.ui.theme.RecordingRedLight
                    else io.github.lrq3000.utterlane.ui.theme.RecordingRed).toArgb()
                awaitPanel { panel!!.findViewById<TextView>(R.id.recording_input_warning).currentTextColor == warningColor }
                instrumentation.runOnMainSync {
                    val warning = panel!!.findViewById<TextView>(R.id.recording_input_warning)
                    assertEquals(View.VISIBLE, warning.visibility)
                    assertEquals(app.getString(R.string.audio_input_continuing,
                        app.getString(R.string.audio_input_bt_disconnected)), warning.text.toString())
                    assertTrue(warning.currentTextColor in listOf(
                        io.github.lrq3000.utterlane.ui.theme.RecordingRed.toArgb(),
                        io.github.lrq3000.utterlane.ui.theme.RecordingRedLight.toArgb()))
                }
                if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("captureEvidence") == "true") {
                    instrumentation.runOnMainSync {
                        val view = panel!!
                        val width = (360 * app.resources.displayMetrics.density).toInt()
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        view.layout(0, 0, width, view.measuredHeight)
                        val image = android.graphics.Bitmap.createBitmap(width, view.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
                        view.draw(android.graphics.Canvas(image))
                        java.io.File(app.getExternalFilesDir(null), "bluetooth-panel-${if (dark) "dark" else "light"}.png")
                            .outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                }
                metrics.samples(ShortArray(800), true)
            }
            metrics.stopping(); metrics.captureEnded()
            awaitPanel {
                panel!!.findViewById<TextView>(R.id.recording_input_warning).text.toString() ==
                    app.getString(R.string.audio_input_fallback_previous, app.getString(R.string.audio_input_bt_disconnected))
            }
        } finally {
            instrumentation.runOnMainSync { panel?.release() }
            scope.cancel()
            app.settingsRepository.setShowTranscriptionStreamStatistics(previous)
            app.settingsRepository.setThemeMode(previousTheme)
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
                val density = app.resources.displayMetrics.density
                panel.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertTrue("Finish tap area remains at least 130 dp tall", button.measuredHeight >= 130 * density)
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
        var now = 0L
        val metrics = CaptureMetrics { now }
        metrics.model("QA stream")
        metrics.captured(32000)
        metrics.recognition(RecognitionActivity(stage = "speaker/transformer", elapsedMillis = 10000,
            sinceProgressMillis = 2000, active = true))
        now += 100; metrics.tick()
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
            now += 100; metrics.tick()
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

    private fun tap(panel: RecordingPanel, bounds: Rect) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue("Waveform touch must have a recipient", panel.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
    }
}
