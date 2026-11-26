package io.github.lrq3000.utterlane.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lrq3000.utterlane.R
import androidx.activity.compose.BackHandler
import io.github.lrq3000.utterlane.ui.RecordingPanel
import io.github.lrq3000.utterlane.ui.theme.LocalBrandPalette

@Composable
internal fun VoiceTrialPage(page: OnboardingPage, state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    val palette = LocalBrandPalette.current
    val trial = state.trial
    if (!trial.active) {
        OnboardingHeading(page)
        Surface(color = palette.container, shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, null, Modifier.size(16.dp), tint = palette.primary)
                Text(state.choice?.name.orEmpty(), style = MaterialTheme.typography.labelSmall, color = palette.onContainer)
            }
        }
    }
    Text(stringResource(R.string.onboarding_your_words), style = MaterialTheme.typography.labelMedium,
        color = palette.muted, modifier = Modifier.padding(top = if (trial.active) 0.dp else 24.dp, bottom = 10.dp))
    TextField(value = trial.text, onValueChange = { action(OnboardingAction.EditText(it)) }, readOnly = trial.active,
        modifier = Modifier.fillMaxWidth().testTag("onboarding_transcript"), minLines = if (trial.active) 2 else 5, maxLines = 9,
        placeholder = { Text(stringResource(R.string.onboarding_voice_placeholder)) }, shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = palette.surface, unfocusedContainerColor = palette.surface,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
    trial.warning?.let { OnboardingNote(it, Icons.Default.Info) }
    if (trial.active) return

    val caption = stringResource(R.string.onboarding_tap_speak)
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(Brush.horizontalGradient(palette.waveform))
            .clickable(enabled = !state.busy && state.modelReady && !trial.releasing, role = Role.Button) { action(OnboardingAction.Record) }
            .testTag("onboarding_record").semantics { contentDescription = caption }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Mic, null, Modifier.size(32.dp), tint = Color.White)
        }
        Text(caption, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.onboarding_no_keyboard), style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
    if (!state.modelReady) OnboardingNote(stringResource(R.string.onboarding_download_needed), Icons.Default.Download)
    trial.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
    OnboardingNote(stringResource(R.string.onboarding_voice_tip), Icons.Default.Mic)
    OnboardingNote(stringResource(R.string.onboarding_voice_history))
    Text(stringResource(R.string.onboarding_trial_limit), style = MaterialTheme.typography.bodySmall, color = palette.muted)
}

@Composable
internal fun VoiceTrialRecordingPanel(session: VoiceTrialPanel, preview: String, maxHeight: Dp, action: (OnboardingAction) -> Unit) {
    key(session) {
        val scope = rememberCoroutineScope()
        val dispatch by rememberUpdatedState(action)
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        var nativePanel by remember { mutableStateOf<RecordingPanel?>(null) }
        BackHandler { if (nativePanel?.dismissSettingsPrompt() != true) dispatch(OnboardingAction.Back) }
        LaunchedEffect(Unit) { focus.clearFocus(force = true); keyboard?.hide() }
        // The cap applies to the scroll viewport, not the native controls: short screens and large fonts can still reach Cancel.
        AndroidView(
            factory = { context ->
                RecordingPanel(context,
                    onStop = { dispatch(OnboardingAction.StopRecording) },
                    onCancel = { dispatch(OnboardingAction.CancelRecording) },
                    onSettings = { dispatch(OnboardingAction.DictationSettings(it)) }
                ).also { nativePanel = it; it.bind(scope, session.metrics, session.speakers) }
            },
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
            onRelease = { it.release() },
            update = { it.preview(preview) }
        )
    }
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
