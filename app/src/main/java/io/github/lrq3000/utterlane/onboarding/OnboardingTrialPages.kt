package io.github.lrq3000.utterlane.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.ui.theme.LocalBrandPalette
import io.github.lrq3000.utterlane.ui.theme.RecordingRed

@Composable
internal fun VoiceTrialPage(page: OnboardingPage, state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    val palette = LocalBrandPalette.current
    val trial = state.trial
    OnboardingHeading(page)
    Surface(color = palette.container, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, Modifier.size(16.dp), tint = palette.primary)
            Text(state.choice?.name.orEmpty(), style = MaterialTheme.typography.labelSmall, color = palette.onContainer)
        }
    }
    Text(stringResource(R.string.onboarding_your_words), style = MaterialTheme.typography.labelMedium,
        color = palette.muted, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
    TextField(value = trial.text, onValueChange = { action(OnboardingAction.EditText(it)) }, readOnly = trial.active,
        modifier = Modifier.fillMaxWidth().testTag("onboarding_transcript"), minLines = 5, maxLines = 9,
        placeholder = { Text(stringResource(R.string.onboarding_voice_placeholder)) }, shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = palette.surface, unfocusedContainerColor = palette.surface,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
    val caption = stringResource(when (trial.phase) {
        TrialPhase.PREPARING -> R.string.model_loading
        TrialPhase.RECORDING -> R.string.onboarding_listening
        TrialPhase.PROCESSING -> R.string.onboarding_processing
        TrialPhase.IDLE -> R.string.onboarding_tap_speak
    })
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val colors = if (trial.phase == TrialPhase.RECORDING) listOf(RecordingRed, RecordingRed) else palette.waveform
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(Brush.horizontalGradient(colors))
            .clickable(enabled = !state.busy && state.modelReady && trial.phase != TrialPhase.PROCESSING, role = Role.Button) { action(OnboardingAction.Record) }
            .testTag("onboarding_record").semantics { contentDescription = caption }, contentAlignment = Alignment.Center) {
            if (trial.phase == TrialPhase.PREPARING || trial.phase == TrialPhase.PROCESSING) CircularProgressIndicator(Modifier.size(30.dp), color = Color.White)
            else Icon(if (trial.phase == TrialPhase.RECORDING) Icons.Default.Stop else Icons.Default.Mic, null, Modifier.size(32.dp), tint = Color.White)
        }
        Text(caption, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.onboarding_no_keyboard), style = MaterialTheme.typography.bodySmall, color = palette.muted)
        if (trial.active) TextButton(onClick = { action(OnboardingAction.CancelRecording) }) { Text(stringResource(R.string.action_cancel)) }
    }
    if (!state.modelReady) OnboardingNote(stringResource(R.string.onboarding_download_needed), Icons.Default.Download)
    trial.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
    trial.warning?.let { OnboardingNote(it, Icons.Default.Info) }
    OnboardingNote(stringResource(R.string.onboarding_voice_tip), Icons.Default.Mic)
    OnboardingNote(stringResource(R.string.onboarding_voice_history))
    Text(stringResource(R.string.onboarding_trial_limit), style = MaterialTheme.typography.bodySmall, color = palette.muted)
}

@Composable
internal fun FileTrialPage(page: OnboardingPage, state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    OnboardingHeading(page)
    OnboardingCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AudioFile, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.onboarding_sample_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.onboarding_sample_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DecorativeWave(Modifier.fillMaxWidth().height(60.dp).padding(top = 8.dp))
        Text(stringResource(R.string.onboarding_sample_attribution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(R.string.onboarding_sample_steps), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 22.dp))
    FilledTonalButton(onClick = { action(OnboardingAction.ShareSample) }, enabled = state.modelReady && !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("onboarding_share_sample"), shape = MaterialTheme.shapes.small) {
        Icon(Icons.Default.Share, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.onboarding_share_sample))
    }
    if (!state.modelReady) OnboardingNote(stringResource(R.string.onboarding_download_needed), Icons.Default.Download)
    OnboardingNote(stringResource(R.string.onboarding_file_note), Icons.Default.AudioFile)
    TextButton(onClick = { action(OnboardingAction.OpenLink(OnboardingContent.SAMPLE_SOURCE)) }) {
        Text(stringResource(R.string.onboarding_sample_source), style = MaterialTheme.typography.labelMedium)
    }
}
