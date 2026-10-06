package io.github.lrq3000.utterlane.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WelcomePage(page: OnboardingPage) {
    OnboardingArt(OnboardingArtKind.WELCOME, Modifier.fillMaxWidth().height(220.dp))
    Spacer(Modifier.height(18.dp))
    OnboardingHeading(page)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(R.string.onboarding_offline, R.string.onboarding_free).forEach { label ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EverydayUsesPage(page: OnboardingPage, action: (OnboardingAction) -> Unit) {
    var sources by rememberSaveable { mutableStateOf(false) }
    OnboardingHeading(page)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onboarding_speed_ratio), style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.onboarding_speed_pace), style = MaterialTheme.typography.bodyMedium)
    }
    Text(stringResource(R.string.onboarding_speed_figures), Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onboarding_speed_scope), Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { sources = true }) { Text(stringResource(R.string.onboarding_speed_sources), style = MaterialTheme.typography.labelSmall) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(22.dp))
    val examples = listOf(
        Triple(OnboardingArtKind.SPEAKING, R.string.onboarding_use_dictation, R.string.onboarding_use_dictation_body),
        Triple(OnboardingArtKind.NOTE, R.string.onboarding_use_file, R.string.onboarding_use_file_body),
        Triple(OnboardingArtKind.CONVERSATION, R.string.onboarding_use_meeting, R.string.onboarding_use_meeting_body)
    )
    val largeText = LocalDensity.current.fontScale >= 1.3f
    examples.forEach { (art, title, copy) ->
        val words: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(copy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OnboardingCard {
            if (largeText) {
                OnboardingArt(art, Modifier.size(76.dp, 80.dp))
                words()
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OnboardingArt(art, Modifier.size(76.dp, 80.dp))
                Box(Modifier.weight(1f)) { words() }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    OnboardingNote(stringResource(R.string.onboarding_local_note))
    if (sources) AlertDialog(onDismissRequest = { sources = false },
        title = { Text(stringResource(R.string.onboarding_speed_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.onboarding_speed_evidence))
                TextButton(onClick = { action(OnboardingAction.OpenLink(OnboardingContent.TYPING_STUDY)) }) { Text(stringResource(R.string.onboarding_typing_study)) }
                TextButton(onClick = { action(OnboardingAction.OpenLink(OnboardingContent.SPEECH_STUDY)) }) { Text(stringResource(R.string.onboarding_speech_study)) }
            }
        }, confirmButton = { TextButton(onClick = { sources = false }) { Text(stringResource(R.string.onboarding_close)) } })
}

@Composable
internal fun CompletionPage(page: OnboardingPage, state: OnboardingUiState) {
    OnboardingArt(OnboardingArtKind.SUCCESS, Modifier.fillMaxWidth().height(114.dp))
    Spacer(Modifier.height(14.dp))
    OnboardingHeading(page)
    Text(stringResource(R.string.onboarding_complete_promise), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.onboarding_summary), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 14.dp))
    val permissions = state.device.permissions
    val preferences = state.device.preferences
    val enabled = stringResource(R.string.onboarding_enabled)
    val off = stringResource(R.string.onboarding_off)
    val allowed = stringResource(R.string.onboarding_allowed)
    val notEnabled = stringResource(R.string.onboarding_not_enabled)
    val shortcuts = buildList {
        if (permissions.keyboard) add(stringResource(R.string.onboarding_keyboard))
        if (state.floatingActive) add(stringResource(R.string.onboarding_floating))
        else if (preferences.floating) add(stringResource(R.string.onboarding_floating_needs_setup))
        if (permissions.accessibility) add(stringResource(R.string.onboarding_accessibility))
    }.joinToString("\n").ifEmpty { stringResource(R.string.onboarding_summary_later) }
    val theme = stringResource(when (preferences.theme) {
        "light" -> R.string.setting_theme_light
        "dark" -> R.string.setting_theme_dark
        else -> R.string.setting_theme_system
    })
    val items = listOf(
        Triple(R.string.onboarding_summary_model, state.choice?.name.orEmpty(), stringResource(if (state.modelReady) R.string.onboarding_summary_ready else R.string.onboarding_summary_selected)),
        Triple(R.string.section_appearance, theme, ""),
        Triple(R.string.onboarding_summary_mic, if (permissions.microphone) allowed else notEnabled, ""),
        Triple(R.string.onboarding_summary_shortcuts, shortcuts, ""),
        Triple(R.string.onboarding_watch_folder, if (preferences.monitor) enabled else off, preferences.folders.sorted().joinToString("\n")),
        Triple(R.string.onboarding_audio_access, if (permissions.audioFiles) allowed else notEnabled, ""),
        Triple(R.string.onboarding_notifications, if (permissions.notifications) allowed else notEnabled, ""),
        Triple(R.string.onboarding_summary_speakers, if (preferences.speakers) enabled else off,
            if (preferences.speakers) if (preferences.speakerCount == 0) stringResource(R.string.diarization_auto) else preferences.speakerCount.toString() else "")
    )
    // Each label/value pair takes the whole width; long names and folder paths
    // wrap vertically instead of competing with another column or a status icon.
    items.forEach { (label, value, detail) ->
        OnboardingCard {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
    }
    Text(stringResource(R.string.onboarding_revisit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp))
}
