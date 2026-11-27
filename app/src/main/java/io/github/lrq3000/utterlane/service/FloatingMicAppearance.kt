package io.github.lrq3000.utterlane.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.github.lrq3000.utterlane.ui.theme.NativeBrandStyle

/** One glyph and one oval for every state. Feedback mutates the fill in place instead of rebuilding views on each frame. */
internal class FloatingMicAppearance(button: ImageView) {
    private val context = button.context
    private val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, null).apply {
        shape = GradientDrawable.OVAL
    }
    private var idle = intArrayOf()
    private val recording = intArrayOf(0xFFCC2E36.toInt(), 0xFFB3261E.toInt(), 0xFF951522.toInt())
    private val hsv = FloatArray(3)
    private var feedback: ValueAnimator? = null

    init { button.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, null) }

    fun show(recordingNow: Boolean, theme: String) {
        idle = NativeBrandStyle.palette(context, theme).waveform.map { it.toArgb() }.toIntArray()
        if (recordingNow) {
            cancelFeedback()
            fill.colors = recording
        } else if (feedback == null) fill.colors = idle
    }

    fun saved() {
        cancelFeedback()
        if (!ValueAnimator.areAnimatorsEnabled()) { fill.colors = idle; return }
        val frame = IntArray(idle.size)
        feedback = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3_000
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (feedback !== it) return@addUpdateListener
                val progress = it.animatedValue as Float
                // AudioRecorder 6590990: one hue revolution, desaturating as it goes, then blend the last 15% back to idle.
                hsv[0] = (360f * progress) % 360f
                hsv[1] = 1f - progress
                hsv[2] = .57f // Preserve contrast against Utterlane's unchanged white mic even at the lightest hues.
                val color = Color.HSVToColor(hsv)
                val settling = ((progress - .85f) / .15f).coerceIn(0f, 1f)
                for (i in frame.indices) frame[i] = ColorUtils.blendARGB(color, idle[i], settling)
                fill.colors = frame
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (feedback !== animation) return
                    feedback = null
                    fill.colors = idle
                }
            })
        }.also { it.start() }
    }

    fun cancelFeedback() {
        val previous = feedback
        feedback = null // Its synchronous end callback must not override a new recording state.
        previous?.cancel()
    }
}
