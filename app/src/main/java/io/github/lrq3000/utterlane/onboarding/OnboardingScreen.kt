package io.github.lrq3000.utterlane.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.ui.theme.LocalBrandPalette

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OnboardingScreen(state: OnboardingUiState, onAction: (OnboardingAction) -> Unit) {
    var disclosure by rememberSaveable { mutableStateOf(false) }
    val dispatch: (OnboardingAction) -> Unit = { action ->
        if (action == OnboardingAction.Settings(AndroidSetup.ACCESSIBILITY) && !state.device.permissions.accessibility) disclosure = true
        else onAction(action)
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxSize().semantics { testTagsAsResourceId = true }) {
                OnboardingHeader(state, dispatch)
                if (!state.ready) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        if (state.error == null) CircularProgressIndicator()
                        else {
                            Text(state.error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { dispatch(OnboardingAction.RetryInitialization) }, enabled = !state.busy) {
                                Text(stringResource(R.string.onboarding_retry))
                            }
                        }
                    }
                } else {
                    key(state.step) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp)) {
                            val page = OnboardingContent.byStep.getValue(state.step)
                            when (state.step) {
                                OnboardingStep.WELCOME -> WelcomePage(page)
                                OnboardingStep.USES -> EverydayUsesPage(page, dispatch)
                                OnboardingStep.MODELS -> ModelChoicePage(page, state, dispatch)
                                OnboardingStep.DOWNLOAD, OnboardingStep.SPEAKER_DOWNLOAD -> ModelDownloadPage(page, state, dispatch)
                                OnboardingStep.MICROPHONE -> MicrophoneSetupPage(page)
                                OnboardingStep.INPUT -> InputSetupPage(page, state, dispatch)
                                OnboardingStep.FOLDERS -> FolderSetupPage(page, state, dispatch)
                                OnboardingStep.SPEAKERS -> SpeakerSetupPage(page, state, dispatch)
                                OnboardingStep.VOICE_TRIAL -> VoiceTrialPage(page, state, dispatch)
                                OnboardingStep.FILE_TRIAL -> FileTrialPage(page, state, dispatch)
                                OnboardingStep.COMPLETE -> CompletionPage(page, state)
                            }
                            state.error?.let { error ->
                                Text(error, color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("onboarding_error"))
                                if (state.step == OnboardingStep.MICROPHONE || state.step == OnboardingStep.VOICE_TRIAL || state.step == OnboardingStep.FOLDERS || state.step == OnboardingStep.INPUT) {
                                    TextButton(onClick = { dispatch(OnboardingAction.Settings(AndroidSetup.APP)) }) {
                                        Text(stringResource(R.string.onboarding_open_settings))
                                    }
                                }
                            }
                        }
                    }
                    OnboardingFooter(state, dispatch)
                }
            }
        }
    }
    if (disclosure) AlertDialog(
        onDismissRequest = { disclosure = false },
        title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
        text = { Text(stringResource(R.string.accessibility_data_use), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            disclosure = false; onAction(OnboardingAction.Settings(AndroidSetup.ACCESSIBILITY))
        }) { Text(stringResource(R.string.accessibility_disclosure_agree)) } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text(stringResource(R.string.accessibility_disclosure_decline)) } }
    )
}

@Composable
private fun OnboardingHeader(state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    val phases = listOf(R.string.onboarding_phase_discover, R.string.onboarding_phase_setup, R.string.onboarding_phase_trial)
    val phase = stringResource(phases[state.step.phase])
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.step == OnboardingStep.WELCOME) {
                Image(painterResource(if (LocalBrandPalette.current.dark) R.drawable.utterlane_wordmark_dark else R.drawable.utterlane_wordmark),
                    contentDescription = stringResource(R.string.app_name), modifier = Modifier.weight(1f).heightIn(max = 42.dp).padding(end = 12.dp))
            } else {
                IconButton(onClick = { action(OnboardingAction.Back) }, Modifier.testTag("onboarding_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.onboarding_back))
                }
                Text(phase, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 8.dp))
            }
            AppearanceSelector(state.device.preferences.theme, action)
        }
        val description = stringResource(R.string.onboarding_stage, phase, state.step.phase + 1)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { this.contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { index ->
                Surface(Modifier.weight(1f).height(3.dp), shape = RoundedCornerShape(4.dp),
                    color = if (index <= state.step.phase) MaterialTheme.colorScheme.primary.copy(alpha = if (index == state.step.phase) 1f else .35f)
                    else MaterialTheme.colorScheme.outlineVariant) {}
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppearanceSelector(selected: String, action: (OnboardingAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf("system" to R.string.setting_theme_system, "light" to R.string.setting_theme_light, "dark" to R.string.setting_theme_dark)
    Column(Modifier.widthIn(max = 156.dp), horizontalAlignment = Alignment.End) {
        Text(stringResource(R.string.section_appearance), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("onboarding_appearance")) {
                    Text(stringResource(choices.firstOrNull { it.first == selected }?.second ?: R.string.setting_theme_system))
                    Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
                }
            }
            // Popups have their own semantics owner; the host's resource-ID
            // setting does not propagate into the dropdown window.
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                choices.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                        expanded = false; action(OnboardingAction.Appearance(value))
                    }, modifier = Modifier.testTag("onboarding_theme_$value"),
                        trailingIcon = { if (value == selected) Icon(Icons.Default.Check, null) })
                }
            }
        }
    }
}

@Composable
private fun OnboardingFooter(state: OnboardingUiState, action: (OnboardingAction) -> Unit) {
    var primaryAction: OnboardingAction = OnboardingAction.Next
    val label = when (state.step) {
        OnboardingStep.WELCOME -> stringResource(R.string.onboarding_begin)
        OnboardingStep.USES -> stringResource(R.string.onboarding_setup_phone)
        OnboardingStep.MODELS -> stringResource(R.string.onboarding_download_action, ((state.choice?.bytes ?: 0) / 1_000_000L).toInt())
        OnboardingStep.DOWNLOAD, OnboardingStep.SPEAKER_DOWNLOAD -> when {
            state.transfer.active || state.busy -> stringResource(if (state.transfer.phase == TransferPhase.VERIFYING) R.string.onboarding_download_verifying else R.string.action_download)
            state.transfer.phase == TransferPhase.READY -> stringResource(R.string.onboarding_continue)
            else -> { primaryAction = OnboardingAction.Download; stringResource(if (state.transfer.phase == TransferPhase.ERROR) R.string.onboarding_retry else R.string.action_download) }
        }
        OnboardingStep.MICROPHONE -> if (!state.device.permissions.microphone) {
            primaryAction = OnboardingAction.Permission(SetupPermission.MICROPHONE); stringResource(R.string.onboarding_allow_mic)
        } else stringResource(R.string.onboarding_continue)
        OnboardingStep.FOLDERS -> stringResource(if (state.progress.monitorWanted && !state.device.preferences.monitor) R.string.onboarding_monitor_enable else R.string.onboarding_continue)
        OnboardingStep.SPEAKERS -> stringResource(if (state.progress.speakersWanted) R.string.onboarding_speaker_download else R.string.onboarding_continue)
        OnboardingStep.FILE_TRIAL -> stringResource(R.string.onboarding_finish_setup)
        OnboardingStep.COMPLETE -> stringResource(R.string.onboarding_finish)
        else -> stringResource(R.string.onboarding_continue)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        val transferPage = state.step == OnboardingStep.DOWNLOAD || state.step == OnboardingStep.SPEAKER_DOWNLOAD || state.step == OnboardingStep.MODELS
        Button(onClick = { action(primaryAction) }, enabled = !state.busy && !state.trial.active && !(transferPage && state.transfer.active),
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("onboarding_next"), shape = MaterialTheme.shapes.small) {
            Text(label, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(19.dp))
        }
        val secondary = when (state.step) {
            OnboardingStep.MODELS -> R.string.onboarding_have_files
            OnboardingStep.DOWNLOAD -> R.string.onboarding_change_model
            OnboardingStep.MICROPHONE -> R.string.onboarding_not_now
            OnboardingStep.INPUT -> R.string.onboarding_input_later
            OnboardingStep.FOLDERS -> R.string.onboarding_monitor_skip
            OnboardingStep.SPEAKERS, OnboardingStep.SPEAKER_DOWNLOAD -> R.string.onboarding_speaker_skip
            OnboardingStep.VOICE_TRIAL, OnboardingStep.FILE_TRIAL -> R.string.onboarding_skip_test
            else -> null
        }
        if (secondary != null) TextButton(onClick = {
            action(when (state.step) {
                OnboardingStep.MODELS -> OnboardingAction.Import(false)
                OnboardingStep.DOWNLOAD -> OnboardingAction.CancelDownload
                else -> OnboardingAction.Skip
            })
        }, enabled = !state.busy || state.step == OnboardingStep.DOWNLOAD || state.step == OnboardingStep.SPEAKER_DOWNLOAD,
            modifier = Modifier.fillMaxWidth().testTag("onboarding_skip")) { Text(stringResource(secondary), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else Text(stringResource(if (state.step == OnboardingStep.WELCOME) R.string.onboarding_no_account else R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
internal fun OnboardingHeading(page: OnboardingPage, heading: Int = page.heading, body: Int = page.body) {
    Text(stringResource(page.kicker), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    val title = stringResource(heading)
    val accent = MaterialTheme.colorScheme.primary
    val lines = title.split('\n', limit = 2)
    val styledTitle = if (lines.size == 2 && (page.step == OnboardingStep.WELCOME || page.step == OnboardingStep.USES || page.step == OnboardingStep.COMPLETE)) {
        buildAnnotatedString { append(lines[0]); append('\n'); withStyle(SpanStyle(color = accent)) { append(lines[1]) } }
    } else AnnotatedString(title)
    Text(styledTitle, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 14.dp).testTag("onboarding_page_${page.step.id}").semantics { this.heading() })
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 22.dp))
}

@Composable
internal fun OnboardingCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun OnboardingNote(text: String, icon: ImageVector = Icons.Default.Shield) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
