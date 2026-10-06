package io.github.lrq3000.utterlane.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.ui.theme.LocalBrandPalette
import kotlin.math.abs
import kotlin.math.sin

enum class OnboardingArtKind { WELCOME, SPEAKING, NOTE, CONVERSATION, DOWNLOAD, MICROPHONE, FOLDER, SUCCESS }

/** Geometry is bundled; theme-dependent colors are applied to separate vector layers. */
@Composable
internal fun OnboardingArt(kind: OnboardingArtKind, modifier: Modifier = Modifier) {
    val palette = LocalBrandPalette.current
    Box(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        when (kind) {
            OnboardingArtKind.SPEAKING -> LayeredIllustration(R.drawable.onboarding_profile, R.drawable.onboarding_sound_waves)
            OnboardingArtKind.CONVERSATION -> LayeredIllustration(R.drawable.onboarding_speaker_left, R.drawable.onboarding_speaker_right)
            OnboardingArtKind.NOTE -> {
                Image(painterResource(R.drawable.onboarding_note), null,
                    Modifier.size(52.dp, 66.dp).graphicsLayer { rotationZ = -8f }, colorFilter = ColorFilter.tint(palette.primary))
                Box(Modifier.align(Alignment.BottomEnd).size(30.dp).clip(CircleShape).background(Brush.horizontalGradient(palette.waveform)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Mic, null, Modifier.size(17.dp), tint = Color.White)
                }
            }
            OnboardingArtKind.WELCOME -> WelcomeIllustration()
            else -> {
                val success = kind == OnboardingArtKind.SUCCESS
                val icon = when (kind) {
                    OnboardingArtKind.DOWNLOAD -> Icons.Default.Memory
                    OnboardingArtKind.MICROPHONE -> Icons.Default.MicNone
                    OnboardingArtKind.FOLDER -> Icons.Default.FolderOpen
                    else -> Icons.Default.Check
                }
                Box(Modifier.size(if (success) 100.dp else 150.dp).border(1.dp, palette.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (success) 64.dp else 88.dp).graphicsLayer { rotationZ = -8f }
                        .clip(RoundedCornerShape(22.dp)).background(Brush.horizontalGradient(palette.header)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(if (success) 32.dp else 44.dp).graphicsLayer { rotationZ = 8f }, tint = palette.primary)
                    }
                    if (kind == OnboardingArtKind.DOWNLOAD) Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp),
                        shape = RoundedCornerShape(12.dp), color = palette.primary) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Download, null, Modifier.size(22.dp), tint = palette.onPrimary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayeredIllustration(primary: Int, secondary: Int) {
    val palette = LocalBrandPalette.current
    Image(painterResource(primary), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(palette.primary))
    Image(painterResource(secondary), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(palette.violet))
}

@Composable
private fun WelcomeIllustration() {
    val palette = LocalBrandPalette.current
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(208.dp).border(1.dp, palette.outlineVariant, CircleShape))
        Surface(Modifier.size(132.dp, 194.dp).graphicsLayer { rotationZ = -8f },
            shape = RoundedCornerShape(22.dp), border = BorderStroke(2.dp, palette.onContainer), color = palette.surface) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(Modifier.align(Alignment.CenterHorizontally).size(24.dp, 3.dp), shape = CircleShape, color = palette.outlineVariant) {}
                Surface(shape = RoundedCornerShape(8.dp), color = palette.background) {
                    Text(stringResource(R.string.onboarding_art_question), Modifier.padding(8.dp), fontSize = 9.sp, lineHeight = 12.sp,
                        color = palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = palette.container) {
                    Text(stringResource(R.string.onboarding_art_reply), Modifier.padding(8.dp), fontSize = 9.sp, lineHeight = 12.sp,
                        color = palette.onContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                DecorativeWave(Modifier.fillMaxWidth().height(40.dp))
            }
        }
        Surface(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 26.dp).size(40.dp).graphicsLayer { rotationZ = 6f },
            shape = RoundedCornerShape(14.dp), color = palette.container) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Shield, null, Modifier.size(24.dp), tint = palette.primary) }
        }
        Surface(Modifier.width(156.dp).offset(x = 50.dp, y = 58.dp).graphicsLayer { rotationZ = 3f },
            shape = RoundedCornerShape(14.dp), color = palette.surface, shadowElevation = 3.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.onboarding_art_ready), fontSize = 9.sp, lineHeight = 11.sp, color = palette.primary, maxLines = 1)
                Text(stringResource(R.string.onboarding_art_reply), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Static illustrative signal, never used as a live recording level indicator. */
@Composable
internal fun DecorativeWave(modifier: Modifier = Modifier) {
    val palette = LocalBrandPalette.current
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.horizontalGradient(palette.waveform)).padding(10.dp)) {
        val count = 23
        val space = size.width / count
        repeat(count) { index ->
            val height = size.height * (.25f + abs(sin(index * 1.83)).toFloat() * .65f)
            val x = (index + .5f) * space
            drawLine(Color.White, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                strokeWidth = (space * .35f).coerceAtLeast(1f), cap = StrokeCap.Round)
        }
    }
}
