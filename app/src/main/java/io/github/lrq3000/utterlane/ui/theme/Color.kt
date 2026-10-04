package io.github.lrq3000.utterlane.ui.theme

import androidx.compose.ui.graphics.Color

/** Shared by Compose and Android Views so voice overlays cannot drift from Settings. */
data class BrandPalette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val outline: Color,
    val outlineVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val container: Color,
    val onContainer: Color,
    val violet: Color,
    val violetContainer: Color,
    val onVioletContainer: Color,
    val header: List<Color>,
    val waveform: List<Color>,
    val warning: Color,
    val dark: Boolean
)

object BlueHarmony {
    val Light = BrandPalette(
        background = Color(0xFFF1F5FB), surface = Color.White,
        text = Color(0xFF15263F), muted = Color(0xFF617088),
        outline = Color(0xFF748198), outlineVariant = Color(0xFFE5EAF3),
        primary = Color(0xFF155ECC), onPrimary = Color.White,
        container = Color(0xFFEAF1FF), onContainer = Color(0xFF233E61),
        violet = Color(0xFF7750BC), violetContainer = Color(0xFFEDE9FC),
        onVioletContainer = Color(0xFF39265D),
        header = listOf(Color(0xFFEDE9FC), Color(0xFFE4EFFF), Color(0xFFE3F6FF)),
        waveform = listOf(Color(0xFF7750BC), Color(0xFF365ECD), Color(0xFF067BC9)),
        warning = Color(0xFF8A4B0B), dark = false
    )
    val Dark = BrandPalette(
        background = Color(0xFF0E1727), surface = Color(0xFF182438),
        text = Color(0xFFE9EFFC), muted = Color(0xFFADBAD0),
        outline = Color(0xFF8393AA), outlineVariant = Color(0xFF29374D),
        primary = Color(0xFFA4C7FF), onPrimary = Color(0xFF082957),
        container = Color(0xFF233954), onContainer = Color(0xFFD6E5FF),
        violet = Color(0xFFD0B7FF), violetContainer = Color(0xFF33284C),
        onVioletContainer = Color(0xFFEBDDFF),
        header = listOf(Color(0xFF292043), Color(0xFF1C3051), Color(0xFF153349)),
        waveform = listOf(Color(0xFF6542AA), Color(0xFF3459B4), Color(0xFF086FAC)),
        warning = Color(0xFFFFBF7A), dark = true
    )

    fun palette(dark: Boolean) = if (dark) Dark else Light
}

// Red remains a semantic state, rather than becoming another decorative accent.
val RecordingRed = Color(0xFFB3261E)
val RecordingRedLight = Color(0xFFF2B8B5)
