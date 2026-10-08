package io.github.lrq3000.utterlane.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.ui.theme.LocalBrandPalette

enum class HomeDestination { RECORD, AUDIO, TRANSCRIPTS }

/** Shared fixed navigation for Home, Settings and legacy history activities. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun HomeNavigationBar(selected: HomeDestination?, onSelect: (HomeDestination) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier.testTag("home_navigation").semantics { testTagsAsResourceId = true }, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        HomeDestination.entries.forEach { destination ->
            val label = stringResource(when (destination) {
                HomeDestination.RECORD -> R.string.home_record
                HomeDestination.AUDIO -> R.string.home_audio
                HomeDestination.TRANSCRIPTS -> R.string.home_transcripts
            })
            NavigationBarItem(selected = selected == destination, onClick = { onSelect(destination) },
                modifier = Modifier.testTag("home_nav_${destination.name.lowercase()}"),
                icon = { Icon(when (destination) {
                    HomeDestination.RECORD -> Icons.Outlined.Mic
                    HomeDestination.AUDIO -> Icons.Outlined.GraphicEq
                    HomeDestination.TRANSCRIPTS -> Icons.Outlined.Description
                }, null) }, label = { Text(label, textAlign = TextAlign.Center) })
        }
    }
}

/** Record uses the original wordmark; history callers can supply their title/back action. */
@Composable
fun HomeHeader(title: String? = null, onBack: (() -> Unit)? = null, onSettings: () -> Unit) {
    val palette = LocalBrandPalette.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Equal gutters center the artwork on the screen, not beside the gear.
        if (onBack == null) Spacer(Modifier.size(48.dp))
        else IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("home_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.history_back))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (title == null) Image(painterResource(if (palette.dark) R.drawable.utterlane_wordmark_dark else R.drawable.utterlane_wordmark),
                stringResource(R.string.app_name), Modifier.widthIn(max = 210.dp).fillMaxWidth()
                    .aspectRatio(1190f / 326f).testTag("brand_wordmark"))
            else Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).testTag("home_settings")) {
            Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
        }
    }
}
