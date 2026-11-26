package io.github.lrq3000.utterlane.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.CapturePhase
import io.github.lrq3000.utterlane.ui.theme.BrandPalette
import io.github.lrq3000.utterlane.ui.theme.NativeBrandStyle

/** Inline confirmation never moves focus to a second window or changes capture on the first tap. */
internal class KeyboardSettingsPrompt(context: Context, private val confirm: (CapturePhase) -> Unit) : LinearLayout(context) {
    private var phase: CapturePhase? = null
    private var showing: CapturePhase? = null
    val gear = ImageButton(context).apply {
        id = R.id.recording_settings_gear
        setImageResource(R.drawable.ic_settings)
        contentDescription = context.getString(R.string.settings_title)
        tooltipText = contentDescription
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        val inset = dp(12)
        setPadding(inset, inset, inset, inset)
        setOnClickListener {
            if (showing != null) dismiss() else phase?.let(::show)
        }
    }
    private val warning = TextView(context).apply {
        textSize = 14f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val back = button(R.id.recording_settings_back) { dismiss() }
    private val proceed = button(R.id.recording_settings_confirm) {
        val requested = showing
        dismiss()
        if (requested != null && requested == phase) confirm(requested)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.END
        addView(warning, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(back, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            addView(proceed, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        applyPalette(NativeBrandStyle.palette(context))
        update(CapturePhase.LOADING)
        dismiss()
    }

    fun update(value: CapturePhase) {
        val next = value.takeIf { it == CapturePhase.CAPTURING || it == CapturePhase.PROCESSING }
        if (phase != next) dismiss()
        phase = next
        gear.isEnabled = next != null
    }

    fun dismiss(): Boolean {
        val wasShowing = showing != null
        showing = null
        visibility = View.GONE
        return wasShowing
    }

    private fun show(phase: CapturePhase) {
        showing = phase
        val recording = phase == CapturePhase.CAPTURING
        warning.setText(if (recording) R.string.dictation_settings_discard_warning else R.string.dictation_settings_background_warning)
        back.setText(if (recording) R.string.dictation_settings_keep_recording else R.string.dictation_settings_back)
        proceed.setText(if (recording) R.string.dictation_settings_discard else R.string.dictation_settings_background)
        visibility = View.VISIBLE
    }

    fun applyPalette(palette: BrandPalette) {
        warning.setTextColor(palette.text.toArgb())
        gear.imageTintList = ColorStateList.valueOf(palette.onContainer.toArgb())
        gear.background = NativeBrandStyle.tonalButton(palette, dp(12).toFloat())
        for (button in listOf(back, proceed)) {
            button.background = NativeBrandStyle.tonalButton(palette, dp(12).toFloat())
            button.setTextColor(palette.onContainer.toArgb())
        }
    }

    private fun button(viewId: Int, action: () -> Unit) = Button(context).apply {
        id = viewId
        isAllCaps = false
        setSingleLine(false)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        minimumHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
