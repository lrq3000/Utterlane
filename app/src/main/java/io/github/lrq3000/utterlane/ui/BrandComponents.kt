package io.github.lrq3000.utterlane.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.ui.theme.LocalBrandPalette

/** Source-derived lettering, not a font approximation of the supplied wordmark. */
@Composable
fun BrandHeader(title: String = stringResource(R.string.settings_title), onBack: (() -> Unit)? = null) {
    val palette = LocalBrandPalette.current
    Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(palette.header))) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = if (onBack == null) 20.dp else 12.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.Center) {
                // Symmetric icon-sized gutters center the wordmark on the banner
                // itself, not just the space remaining beside the right-side icon.
                // Bound its width before filling, so landscape stays compact.
                Image(
                    painterResource(if (palette.dark) R.drawable.utterlane_wordmark_dark else R.drawable.utterlane_wordmark),
                    stringResource(R.string.app_name),
                    Modifier.testTag("brand_wordmark").padding(horizontal = 54.dp).widthIn(max = 290.dp)
                        .fillMaxWidth().aspectRatio(1190f / 326f)
                )
                if (onBack != null) IconButton(onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart).size(48.dp).testTag("history_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.history_back), tint = palette.text)
                }
                Image(painterResource(R.drawable.utterlane_icon), null,
                    Modifier.align(if (onBack == null) AbsoluteAlignment.CenterRight else Alignment.CenterEnd).size(42.dp))
            }
            Text(title, style = if (onBack == null) MaterialTheme.typography.labelLarge else MaterialTheme.typography.headlineSmall,
                color = if (onBack == null) palette.muted else palette.text, modifier = Modifier.padding(top = 12.dp))
        }
        // Decorative identity only; unlike the recording waveform, these bars
        // never represent audio or imply a live microphone.
        val ink = palette.text.copy(alpha = 0.14f)
        Canvas(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 8.dp).size(56.dp, 24.dp)) {
            val levels = floatArrayOf(.18f, .5f, 1f, .65f, .3f, .8f, .25f)
            levels.forEachIndexed { index, level ->
                val x = (index + .5f) * size.width / levels.size
                drawLine(ink, Offset(x, size.height * (1 - level) / 2),
                    Offset(x, size.height * (1 + level) / 2), 3.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
fun BrandSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), content = content)
        }
    }
}
