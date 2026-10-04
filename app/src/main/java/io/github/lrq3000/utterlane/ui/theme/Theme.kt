package io.github.lrq3000.utterlane.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val LocalBrandPalette = staticCompositionLocalOf { BlueHarmony.Light }

private fun BrandPalette.materialColors() = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = container, onPrimaryContainer = onContainer,
    secondary = primary, onSecondary = onPrimary,
    secondaryContainer = container, onSecondaryContainer = onContainer,
    tertiary = violet, onTertiary = if (dark) BlueHarmony.Light.onVioletContainer else androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = violetContainer, onTertiaryContainer = onVioletContainer,
    background = background, onBackground = text,
    surface = surface, onSurface = text,
    surfaceVariant = container, onSurfaceVariant = muted,
    surfaceTint = primary, outline = outline, outlineVariant = outlineVariant,
    inverseSurface = if (dark) BlueHarmony.Light.surface else BlueHarmony.Dark.surface,
    inverseOnSurface = if (dark) BlueHarmony.Light.text else BlueHarmony.Dark.text,
    inversePrimary = if (dark) BlueHarmony.Light.primary else BlueHarmony.Dark.primary
)

private val LightColorScheme = BlueHarmony.Light.materialColors()
private val DarkColorScheme = BlueHarmony.Dark.materialColors()
private val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun UtterlaneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Wallpaper-derived colors would replace the approved identity on Android 12+.
    val palette = BlueHarmony.palette(darkTheme)
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = palette.header.first().toArgb()
            window.navigationBarColor = palette.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalBrandPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, shapes = BrandShapes, content = content)
    }
}
