package io.github.lrq3000.utterlane.service

import io.github.lrq3000.utterlane.settings.FloatingButtonSize
import kotlin.math.roundToInt

/** Full-display coordinates, with safe insets applied exactly once by the overlay owner. */
internal class FloatingControlGeometry(
    private val width: Int,
    private val height: Int,
    private val left: Int = 0,
    private val top: Int = 0,
    private val right: Int = 0,
    private val bottom: Int = 0
) {
    fun diameterPx(dp: Int, density: Float, horizontalPadding: Int, verticalPadding: Int): Int {
        val available = minOf(width - left - right - horizontalPadding, height - top - bottom - verticalPadding)
        // Tiny/transient displays may not fit even the minimum preset. Fit the view
        // now, retaining the preferred dp value for when normal bounds return.
        return (FloatingButtonSize.bounded(dp) * density).roundToInt().coerceIn(1, available.coerceAtLeast(1))
    }

    fun clampPosition(x: Int, y: Int, windowWidth: Int, windowHeight: Int, rtl: Boolean): Pair<Int, Int> {
        // LayoutParams.x is measured from START, not necessarily the physical left.
        val start = if (rtl) right else left
        val end = if (rtl) left else right
        return x.coerceIn(start, (width - end - windowWidth).coerceAtLeast(start)) to
            y.coerceIn(top, (height - bottom - windowHeight).coerceAtLeast(top))
    }
}
