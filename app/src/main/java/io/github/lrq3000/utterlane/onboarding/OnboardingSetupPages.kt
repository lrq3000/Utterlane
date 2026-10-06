package io.github.lrq3000.utterlane.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.ui.theme.LocalBrandPalette

@Composable
internal fun ModelChoicePage(page: OnboardingPage, state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    OnboardingHeading(page)
    Text(if (state.device.ramBytes > 0) stringResource(R.string.onboarding_ram, state.device.ramBytes / 1073741824.0)
        else stringResource(R.string.onboarding_ram_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.device.models.forEach { model ->
            val selected = state.choice?.id == model.id
            Surface(Modifier.fillMaxWidth().selectable(selected, enabled = !state.busy, role = Role.RadioButton,
                onClick = { action(OnboardingAction.SelectModel(model.id)) }).testTag("onboarding_model_${model.id}"),
                shape = MaterialTheme.shapes.large,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.id == state.recommendedId) Text(stringResource(R.string.onboarding_recommended),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        RadioButton(selected, onClick = null, modifier = Modifier.size(24.dp))
                    }
                    Text(stringResource(when (model.explanation) {
                        ModelExplanation.ULTRA_Q8 -> R.string.onboarding_model_q8
                        ModelExplanation.ULTRA_Q4 -> R.string.onboarding_model_q4
                        ModelExplanation.REDUX -> R.string.onboarding_model_redux
                        ModelExplanation.ORIGINAL -> R.string.onboarding_model_original
                        ModelExplanation.CURRENT -> R.string.onboarding_model_current
                    }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.onboarding_download_size, (model.bytes / 1_000_000L).toInt()),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    OnboardingNote(stringResource(R.string.onboarding_ram_note), Icons.Default.Memory)
}

@Composable
internal fun ModelDownloadPage(page: OnboardingPage, state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    val transfer = state.transfer
    val speaker = state.step == OnboardingStep.SPEAKER_DOWNLOAD
    val bytes = if (speaker) state.device.speakerBytes else state.choice?.bytes ?: 0
    val ready = transfer.phase == TransferPhase.READY
    val error = transfer.phase == TransferPhase.ERROR
    OnboardingArt(if (ready) OnboardingArtKind.SUCCESS else OnboardingArtKind.DOWNLOAD, Modifier.fillMaxWidth().height(174.dp))
    OnboardingHeading(page,
        heading = if (ready) R.string.onboarding_download_ready else if (error) R.string.onboarding_download_error else page.heading,
        body = if (ready) R.string.onboarding_download_ready_body else if (error) R.string.onboarding_download_error_body else page.body)
    OnboardingCard {
        Text(if (speaker) stringResource(R.string.onboarding_speaker_model) else state.choice?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
        Text(when (transfer.phase) {
            TransferPhase.READY -> stringResource(R.string.onboarding_installed)
            TransferPhase.VERIFYING -> stringResource(R.string.onboarding_download_verifying)
            TransferPhase.IDLE -> stringResource(R.string.onboarding_download_pending, (bytes / 1_000_000L).toInt())
            else -> stringResource(R.string.onboarding_download_progress, (bytes * transfer.progress / 100 / 1_000_000L).toInt(), (bytes / 1_000_000L).toInt(), transfer.progress)
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (transfer.active && transfer.phase == TransferPhase.VERIFYING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        else LinearProgressIndicator(progress = { transfer.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        transfer.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        if (transfer.active) Text(stringResource(R.string.onboarding_download_keep_open), style = MaterialTheme.typography.bodySmall)
    }
    if (!transfer.active && !state.busy && !ready) TextButton(onClick = { action(OnboardingAction.Import(speaker)) }) {
        Text(stringResource(R.string.action_load_local))
    }
    OnboardingNote(stringResource(R.string.onboarding_download_private))
}

@Composable
internal fun MicrophoneSetupPage(page: OnboardingPage) {
    OnboardingArt(OnboardingArtKind.MICROPHONE, Modifier.fillMaxWidth().height(160.dp))
    OnboardingHeading(page)
    SetupInformation(Icons.Default.Mic, R.string.onboarding_mic_start_title, R.string.onboarding_mic_start_body)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SetupInformation(Icons.Default.Shield, R.string.onboarding_mic_local_title, R.string.onboarding_mic_local_body)
    OnboardingNote(stringResource(R.string.onboarding_mic_optional), Icons.Default.AudioFile)
}

@Composable
private fun SetupInformation(icon: ImageVector, title: Int, body: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupCard(icon: ImageVector, title: Int, body: Int, content: @Composable ColumnScope.() -> Unit) {
    OnboardingCard {
        SetupInformation(icon, title, body)
        content()
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun StatusOrAction(enabled: Boolean, label: Int, action: () -> Unit, available: Boolean = true) {
    if (enabled) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.onboarding_enabled), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    } else TextButton(onClick = action, enabled = available) { Text(stringResource(label)) }
}

@Composable
internal fun InputSetupPage(page: OnboardingPage, state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    OnboardingHeading(page)
    val granted = state.device.permissions
    SetupCard(Icons.Default.Keyboard, R.string.onboarding_keyboard, R.string.onboarding_keyboard_body) {
        StatusOrAction(granted.keyboard, R.string.onboarding_keyboard_action, { action(OnboardingAction.Settings(AndroidSetup.KEYBOARD)) })
    }
    SetupCard(Icons.Default.Mic, R.string.onboarding_floating, R.string.onboarding_floating_body) {
        val (label, next) = when {
            !granted.microphone -> R.string.onboarding_allow_mic to OnboardingAction.Permission(SetupPermission.MICROPHONE)
            !granted.overlay -> R.string.onboarding_overlay_action to OnboardingAction.Settings(AndroidSetup.OVERLAY)
            !granted.accessibility -> R.string.onboarding_accessibility_action to OnboardingAction.Settings(AndroidSetup.ACCESSIBILITY)
            else -> R.string.onboarding_floating_enable to OnboardingAction.EnableFloating
        }
        StatusOrAction(state.floatingActive, label, { action(next) }, available = !state.busy && state.modelReady)
        if (!state.modelReady) Text(stringResource(R.string.onboarding_download_needed), style = MaterialTheme.typography.bodySmall)
        if (!granted.notifications) TextButton(onClick = { action(OnboardingAction.Permission(SetupPermission.NOTIFICATIONS)) }) {
            Text(stringResource(R.string.onboarding_notifications_action))
        }
    }
    SetupCard(Icons.Default.Accessibility, R.string.onboarding_accessibility, R.string.onboarding_accessibility_body) {
        StatusOrAction(granted.accessibility, R.string.onboarding_accessibility_action, { action(OnboardingAction.Settings(AndroidSetup.ACCESSIBILITY)) })
    }
}

@Composable
private fun SetupSwitch(title: Int, help: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
internal fun FolderSetupPage(page: OnboardingPage, state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    if (!state.progress.monitorWanted) OnboardingArt(OnboardingArtKind.FOLDER, Modifier.fillMaxWidth().height(140.dp))
    OnboardingHeading(page)
    SetupSwitch(R.string.onboarding_watch_folder, R.string.onboarding_folder_choice, state.progress.monitorWanted, !state.busy) { action(OnboardingAction.Monitor(it)) }
    Spacer(Modifier.height(14.dp))
    if (state.progress.monitorWanted) {
        OnboardingCard {
            Text(stringResource(R.string.onboarding_choose_folder), style = MaterialTheme.typography.titleMedium)
            state.device.preferences.folders.sorted().forEach { path ->
                Text(path, style = MaterialTheme.typography.bodyMedium)
                val description = stringResource(R.string.onboarding_remove_folder, path)
                TextButton(onClick = { action(OnboardingAction.RemoveFolder(path)) }, enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = description }) {
                    Text(stringResource(R.string.action_remove))
                }
            }
            TextButton(onClick = { action(OnboardingAction.ChooseFolder) }) { Text(stringResource(R.string.action_select_folder)) }
            TextButton(onClick = { action(OnboardingAction.ChooseDownloads) }) { Text(stringResource(R.string.onboarding_use_downloads)) }
        }
        Spacer(Modifier.height(12.dp))
        SetupCard(Icons.Default.AudioFile, R.string.onboarding_audio_access, R.string.onboarding_audio_access_body) {
            StatusOrAction(state.device.permissions.audioFiles, R.string.onboarding_audio_access_action, { action(OnboardingAction.Permission(SetupPermission.AUDIO_FILES)) })
        }
        SetupCard(Icons.Default.NotificationsNone, R.string.onboarding_notifications, R.string.onboarding_notifications_body) {
            StatusOrAction(state.device.permissions.notifications, R.string.onboarding_notifications_action, { action(OnboardingAction.Permission(SetupPermission.NOTIFICATIONS)) })
        }
    }
    OnboardingNote(stringResource(R.string.onboarding_monitor_note))
}

@Composable
internal fun SpeakerSetupPage(page: OnboardingPage, state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    val palette = LocalBrandPalette.current
    OnboardingHeading(page)
    listOf(R.string.onboarding_speaker_one to R.string.onboarding_speaker_question, R.string.onboarding_speaker_two to R.string.onboarding_speaker_reply).forEachIndexed { index, (label, sentence) ->
        Surface(Modifier.fillMaxWidth().padding(start = if (index == 1) 32.dp else 0.dp, end = if (index == 0) 30.dp else 0.dp, bottom = 12.dp),
            color = if (index == 0) palette.container else palette.violetContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(sentence), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SetupSwitch(R.string.diarization_enable, R.string.onboarding_speakers_help, state.progress.speakersWanted, !state.busy) { action(OnboardingAction.Speakers(it)) }
    Spacer(Modifier.height(14.dp))
    OnboardingCard {
        Text(stringResource(R.string.onboarding_speaker_size, (state.device.speakerBytes / 1_000_000L).toInt()), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.onboarding_speaker_cost), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.progress.speakersWanted) TextButton(onClick = { action(OnboardingAction.Import(true)) }, enabled = !state.busy) {
        Text(stringResource(R.string.action_load_local))
    }
    OnboardingNote(if (state.device.preferences.speakerCount == 0) stringResource(R.string.onboarding_speaker_note)
        else stringResource(R.string.onboarding_speaker_note_fixed, state.device.preferences.speakerCount), Icons.Default.PeopleOutline)
}
