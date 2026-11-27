package io.github.lrq3000.utterlane.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import io.github.lrq3000.utterlane.R
import kotlin.math.roundToInt

/** Non-intercepting red drop target adapted from lrq3000/AudioRecorder’s OverlayDismissTarget (Apache-2.0, 6590990). */
internal class OverlayDismissTarget(private val context: Context, private val windows: WindowManager) {
    private var view: ImageView? = null
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var highlighted = false

    fun show(geometry: FloatingControlGeometry) {
        if (view != null) return
        val density = context.resources.displayMetrics.density
        val (x, y, size) = geometry.bottomTarget((64 * density).roundToInt(), (16 * density).roundToInt())
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = context.getString(R.string.floating_hide)
            val padding = (size * 0.26f).roundToInt()
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(198, 40, 40))
            }
            // Reserve a stable window for the highlight; the hit area never follows the animation.
            scaleX = .88f
            scaleY = .88f
        }
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x
            this.y = y
            // Non-touchable application overlays at higher opacity can block another app’s input on Android 12+.
            alpha = .8f
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try { windows.addView(icon, params) }
        catch (error: WindowManager.BadTokenException) {
            Log.w("OverlayDismissTarget", "Could not show the floating mic drop target", error)
            return
        }
        radius = size / 2f
        centerX = x + radius
        centerY = y + radius
        view = icon
    }

    fun contains(bubbleX: Float, bubbleY: Float, bubbleRadius: Float): Boolean {
        if (view == null) return false
        val dx = centerX - bubbleX
        val dy = centerY - bubbleY
        val range = radius + bubbleRadius
        return dx * dx + dy * dy <= range * range
    }

    fun highlight(hit: Boolean, bubble: View) {
        if (hit == highlighted) return
        highlighted = hit
        val scale = if (hit) 1f else .88f
        view?.animate()?.scaleX(scale)?.scaleY(scale)?.setDuration(100L)?.start()
        if (hit) bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun hide() {
        view?.let { it.animate().cancel(); windows.removeView(it) }
        view = null
        highlighted = false
    }
}
