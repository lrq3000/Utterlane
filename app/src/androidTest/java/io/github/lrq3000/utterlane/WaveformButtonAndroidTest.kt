package io.github.lrq3000.utterlane

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrq3000.utterlane.ui.WaveformButton
import io.github.lrq3000.utterlane.ui.theme.BlueHarmony
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveformButtonAndroidTest {
    @Test fun idleDrawsAContinuousLineEvenWithStalePcmAndKeepsTheStartAction() = onMain { button ->
        var started = false
        button.text = "Start recording"
        button.contentDescription = button.text
        button.setOnClickListener { started = true }
        button.levels = FloatArray(64) { 1f }
        button.isIdle = true
        button.applyPalette(BlueHarmony.Dark)
        assertTrue(button.background is RippleDrawable)
        val ripple = button.background
        button.applyPalette(BlueHarmony.Dark)
        assertSame("PCM-only AndroidView updates must preserve ongoing touch feedback", ripple, button.background)

        val idle = render(button)
        try {
            val inset = (24 * button.resources.displayMetrics.density).toInt() + 2
            val center = (idle.height * 0.38f).toInt()
            // Every pixel across the baseline must be white: zero-height bars
            // leave gaps and would misrepresent idle as a captured signal.
            for (x in inset until idle.width - inset) assertEquals(Color.WHITE, idle.getPixel(x, center))
            assertEquals(0, whitePixelsAt(idle, (idle.height * 0.23f).toInt()))
            assertEquals("Start recording", button.contentDescription.toString())
            assertTrue(button.performClick())
            assertTrue(started)
        } finally { idle.recycle() }

        var stopped = false
        button.isIdle = false
        button.text = "Finish recording"
        button.contentDescription = button.text
        button.setOnClickListener { stopped = true }
        val capturing = render(button)
        try {
            assertTrue("Actual PCM must draw above the baseline", whitePixelsAt(capturing, (capturing.height * 0.23f).toInt()) > 0)
            assertEquals("Finish recording", button.contentDescription.toString())
            assertTrue(button.performClick())
            assertTrue(stopped)
        } finally { capturing.recycle() }
    }

    @Test fun sharedControlBoundsOversizedInputAndAcceptsEmptyLevels() = onMain { button ->
        val levels = FloatArray(1000) { it / 1000f }
        button.levels = levels
        assertArrayEquals(levels.copyOfRange(936, 1000), button.levels, 0f)
        button.levels = floatArrayOf()
        for (idle in listOf(false, true)) {
            button.isIdle = idle
            val bitmap = render(button)
            try {
                assertEquals(0, whitePixelsAt(bitmap, (bitmap.height * 0.23f).toInt()))
                if (idle) assertEquals(Color.WHITE, bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.38f).toInt()))
            } finally { bitmap.recycle() }
        }
    }

    private fun onMain(check: (WaveformButton) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { check(WaveformButton(instrumentation.targetContext)) }
    }

    private fun render(button: WaveformButton): Bitmap {
        val density = button.resources.displayMetrics.density
        val width = (360 * density).toInt()
        val height = (136 * density).toInt()
        button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        button.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { button.draw(Canvas(it)) }
    }

    private fun whitePixelsAt(bitmap: Bitmap, y: Int): Int =
        (0 until bitmap.width).count { bitmap.getPixel(it, y) == Color.WHITE }
}
