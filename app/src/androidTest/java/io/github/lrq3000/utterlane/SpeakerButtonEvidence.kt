package io.github.lrq3000.utterlane

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import java.io.File

/** Export only the button, never the recording, editor, or surrounding screen. */
internal fun saveSpeakerCrop(name: String, bounds: Rect) {
    if (InstrumentationRegistry.getArguments().getString("speakerVisualEvidence") != "true") return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        writeCrop(screen, bounds, name)
    } finally { screen.recycle() }
}

private fun writeCrop(screen: Bitmap, bounds: Rect, name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
    val dir = File(context.getExternalFilesDir(null), "speaker-visual").apply { mkdirs() }
    try { File(dir, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    finally { crop.recycle() }
}

/** Check a real IME screenshot against Home's vector mask. Sample the local background each row so this works on both a gradient and a selected disc. */
internal suspend fun verifyKeyboardSpeaker(name: String, locate: () -> Rect?, ink: Int) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val glyph = checkNotNull(context.getDrawable(R.drawable.ic_speaker_conversation)).mutate()
    val size = glyph.intrinsicWidth
    val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    glyph.setBounds(0, 0, size, size)
    glyph.draw(Canvas(mask))
    var score = 0.0
    val deadline = android.os.SystemClock.uptimeMillis() + 5000
    try {
        do {
            instrumentation.waitForIdleSync()
            val bounds = locate()
            if (bounds != null && bounds.width() >= size && bounds.height() >= size) {
                val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    if (Rect(0, 0, screen.width, screen.height).contains(bounds)) {
                        val left = bounds.centerX() - size / 2
                        val top = bounds.centerY() - size / 2
                        var intersection = 0
                        var union = 0
                        for (y in 0 until size) {
                            val bg = screen.getPixel(left, top + y)
                            val visibleInk = ColorUtils.compositeColors(ink, bg)
                            for (x in 0 until size) {
                                val pixel = screen.getPixel(left + x, top + y)
                                val visible = distance(pixel, visibleInk) < distance(pixel, bg)
                                val expected = Color.alpha(mask.getPixel(x, y)) >= 128
                                if (expected || visible) union++
                                if (expected && visible) intersection++
                            }
                        }
                        score = intersection.toDouble() / union.coerceAtLeast(1)
                        if (score >= .85) {
                            writeCrop(screen, bounds, name)
                            return
                        }
                    }
                } finally { screen.recycle() }
            }
            delay(60)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        throw AssertionError("$name: keyboard speaker shape overlap $score (expected at least .85)")
    } finally { mask.recycle() }
}

private fun distance(a: Int, b: Int): Int {
    val r = Color.red(a) - Color.red(b)
    val g = Color.green(a) - Color.green(b)
    val blue = Color.blue(a) - Color.blue(b)
    return r * r + g * g + blue * blue
}
