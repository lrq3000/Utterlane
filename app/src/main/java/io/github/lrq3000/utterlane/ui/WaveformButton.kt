package io.github.lrq3000.utterlane.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.widget.Button
import io.github.lrq3000.utterlane.ui.theme.BrandPalette
import io.github.lrq3000.utterlane.ui.theme.NativeBrandStyle

/**
 * The whole waveform is a native accessible Button. Only actual PCM levels drive its bars.
 * Native panels and Compose AndroidView callers supply text, contentDescription and
 * setOnClickListener; idle changes presentation, never whether the button is tappable.
 */
class WaveformButton(context: Context) : Button(context) {
    /** Treat each array as an immutable snapshot. Only the latest 64 levels are retained. */
    var levels = FloatArray(64)
        set(value) {
            if (field !== value) {
                field = if (value.size > 64) value.copyOfRange(value.size - 64, value.size) else value
                invalidate()
            }
        }

    /** No capture is taking place: draw a continuous flat line, even if old PCM remains. */
    var isIdle = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private var appliedPalette: BrandPalette? = null

    init {
        applyPalette(NativeBrandStyle.palette(context))
        setTextColor(Color.WHITE); textSize = 16f
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        setPadding(12, 12, 12, (16 * resources.displayMetrics.density).toInt())
        isAllCaps = false
    }

    fun applyPalette(palette: BrandPalette) {
        // AndroidView updates can arrive for every PCM frame. Keep the current
        // ripple (and avoid drawable allocation) when only the levels changed.
        if (appliedPalette == palette) return
        appliedPalette = palette
        backgroundTintList = null
        background = NativeBrandStyle.waveform(palette, 24 * resources.displayMetrics.density)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 24 * resources.displayMetrics.density
        val available = (width - 2 * padding).coerceAtLeast(1f)
        val center = height * 0.38f
        if (isIdle) {
            paint.strokeWidth = (available / 64 * 0.55f).coerceAtLeast(2f)
            canvas.drawLine(padding, center, padding + available, center, paint)
            return
        }
        if (levels.isEmpty()) return
        paint.strokeWidth = (available / levels.size * 0.55f).coerceAtLeast(2f)
        levels.forEachIndexed { index, level ->
            val x = padding + index * available / levels.size
            val amplitude = level * height * 0.25f
            canvas.drawLine(x, center - amplitude, x, center + amplitude.coerceAtLeast(1f), paint)
        }
    }
}
