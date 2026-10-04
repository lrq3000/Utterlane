package io.github.lrq3000.utterlane.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.compose.ui.graphics.toArgb
import io.github.lrq3000.utterlane.settings.SettingsRepository

/** Native controls retain Android touch feedback while using the Compose palette. */
object NativeBrandStyle {
    fun palette(context: Context, mode: String = SettingsRepository.THEME_SYSTEM): BrandPalette {
        val dark = when (mode) {
            SettingsRepository.THEME_DARK -> true
            SettingsRepository.THEME_LIGHT -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        return BlueHarmony.palette(dark)
    }

    fun waveform(palette: BrandPalette, radius: Float, oval: Boolean = false): RippleDrawable {
        val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            palette.waveform.map { it.toArgb() }.toIntArray()).apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = radius
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, null)
    }

    fun tonalButton(palette: BrandPalette, radius: Float): RippleDrawable {
        val fill = GradientDrawable().apply {
            setColor(palette.container.toArgb())
            cornerRadius = radius
        }
        return RippleDrawable(ColorStateList.valueOf(palette.primary.copy(alpha = .18f).toArgb()), fill, null)
    }
}
