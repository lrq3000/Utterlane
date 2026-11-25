package io.github.lrq3000.utterlane.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.PathParser
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.ui.theme.BrandPalette
import kotlin.math.roundToInt

/** Rasterize the 24 dp Home glyph and the 40 dp disc together, at the keyboard's display density. Some IME windows showed the disc but lost a separately drawn or tinted glyph. */
internal fun waveformSpeakerBackground(context: Context, palette: BrandPalette): RippleDrawable {
    val resources = context.resources
    val density = resources.displayMetrics.density
    val size = (48 * density).roundToInt()
    val glyphSize = (24 * density).roundToInt()
    val path = PathParser.createPathFromPathData(resources.getString(R.string.speaker_people_path))
    fun face(checked: Boolean): BitmapDrawable {
        val image = Bitmap.createBitmap(resources.displayMetrics, size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (checked) {
            paint.color = palette.container.toArgb()
            canvas.drawCircle(size / 2f, size / 2f, 20 * density, paint)
        }
        paint.color = if (checked) palette.primary.toArgb() else 0xC7FFFFFF.toInt()
        val offset = (size - glyphSize) / 2f
        canvas.translate(offset, offset)
        canvas.scale(glyphSize / 24f, glyphSize / 24f)
        canvas.drawPath(path, paint)
        return BitmapDrawable(resources, image)
    }
    val states = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), face(true))
        addState(intArrayOf(), face(false))
    }
    val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, InsetDrawable(mask, (4 * density).roundToInt()))
}
