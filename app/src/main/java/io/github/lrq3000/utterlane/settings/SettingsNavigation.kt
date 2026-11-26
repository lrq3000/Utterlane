package io.github.lrq3000.utterlane.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.asr.LiveSpeakerControl
import io.github.lrq3000.utterlane.history.HistoryCleanupCoordinator

internal enum class SettingsPage(
    val route: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val icon: ImageVector
) {
    ROOT("root", R.string.settings_title, R.string.settings_title, Icons.Default.Settings),
    SPEECH("speech", R.string.settings_speech_title, R.string.settings_speech_summary, Icons.Default.Memory),
    SPEAKERS("speakers", R.string.settings_speakers_title, R.string.settings_speakers_summary, Icons.Default.RecordVoiceOver),
    VOICE("voice", R.string.settings_voice_title, R.string.settings_voice_summary, Icons.Default.Mic),
    FILES("files", R.string.settings_files_title, R.string.settings_files_summary, Icons.Default.FolderOpen),
    STORAGE("storage", R.string.settings_storage_title, R.string.settings_storage_summary, Icons.Default.Storage),
    APPEARANCE("appearance", R.string.settings_appearance_title, R.string.settings_appearance_summary, Icons.Default.Palette);

    companion object {
        fun fromRoute(route: String?): SettingsPage? = entries.firstOrNull { it.route == route }
    }
}

/** Used where a missing model is recoverable without abandoning the user's recording or file. */
@Composable
internal fun SpeakerModelNotice() {
    val context = LocalContext.current
    Column {
        Text(stringResource(R.string.speaker_model_needed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { SettingsActivity.openSpeakerSettings(context) }, Modifier.testTag("open_speaker_settings")) {
            Text(stringResource(R.string.open_speaker_settings))
        }
    }
}

/** Consume the explicit request only after the recording owner can safely hand over its text. */
internal fun LiveSpeakerControl.speakerSettingsAction(context: Context): (() -> Unit)? =
    if (takeSettingsRequest()) ({ SettingsActivity.openSpeakerSettings(context) }) else null

internal fun openSettingsFromDictation(context: Context) {
    val target = Intent(context, SettingsActivity::class.java).putExtra(HistoryCleanupCoordinator.INTERNAL_NAVIGATION, true)
    if (context !is Activity) target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(target) }
    catch (error: RuntimeException) {
        Log.w("SettingsActivity", "Could not open Settings from dictation", error)
        Toast.makeText(context, R.string.dictation_settings_open_failed, Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun SettingsDirectory(onOpenPage: (SettingsPage) -> Unit) {
    for (page in SettingsPage.entries) {
        if (page == SettingsPage.ROOT) continue
        ListItem(
            headlineContent = { Text(stringResource(page.title)) },
            supportingContent = { Text(stringResource(page.summary)) },
            leadingContent = { Icon(page.icon, null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            modifier = Modifier.testTag("settings_open_${page.route}").clickable { onOpenPage(page) }
        )
    }
}
