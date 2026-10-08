package io.github.lrq3000.utterlane.transcribe

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AudioPlaybackControls(model: TranscriptionDialogModel, actions: @Composable RowScope.() -> Unit = {}) {
    val source by model.state.collectAsStateWithLifecycle()
    val player = UtterlaneApp.instance.audioPlayback
    val playback by player.state.collectAsStateWithLifecycle()
    val owner = model.playbackOwner
    val expanded = playback.owner == owner && playback.active
    var scrubbing by remember(playback.audioId, owner) { mutableStateOf<Float?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalContext.current as? Activity
    DisposableEffect(lifecycle, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) player.pause(owner)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(source.visualRate) { player.setRate(owner, source.visualRate) }
    LaunchedEffect(expanded) { if (!expanded) scrubbing = null }
    val duration = if (expanded) playback.durationMs else source.audio?.durationMs ?: 0
    val fraction = scrubbing ?: if (expanded) (playback.positionMs.toDouble() / duration.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) else 0f
    val total = if (duration > 0) audioTime(duration) else stringResource(R.string.audio_duration_unknown)
    val position = if (scrubbing != null) (fraction.toDouble() * duration).toLong() else if (expanded) playback.positionMs else 0L
    val timeline: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Slider(value = fraction, onValueChange = { scrubbing = it }, onValueChangeFinished = {
                scrubbing?.let { player.seek(owner, (it.toDouble() * duration).toLong()) }
                scrubbing = null
            }, enabled = expanded && !playback.preparing && duration > 0 && !source.deleting,
                modifier = Modifier.height(32.dp).testTag("audio_seek"))
            Text("${audioTime(position)} / $total", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }) {
        // Stop adds one target while playback is active. On narrow phones only
        // the timeline drops below, leaving the transcript actions within reach.
        val separateTimeline = expanded && maxWidth < 340.dp
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                DialogAction(if (expanded && playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    stringResource(if (!expanded) R.string.audio_play else if (playback.playing) R.string.audio_pause else R.string.audio_resume),
                    if (expanded) "audio_pause" else "audio_play",
                    source.audio != null && !source.closing && !source.deleting && (!expanded || !playback.preparing), onClick = {
                        if (expanded && playback.playing) player.pause(owner)
                        else source.audio?.let { player.play(owner, it.id, source.visualRate) }
                    })
                if (expanded) DialogAction(Icons.Outlined.Stop, stringResource(R.string.audio_stop), "audio_stop",
                    !source.deleting, onClick = { scrubbing = null; player.stop(owner) })
                if (separateTimeline) Spacer(Modifier.weight(1f))
                else Box(Modifier.weight(1f)) { timeline() }
                VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
                actions()
            }
            if (separateTimeline) timeline()
            if (expanded && playback.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (playback.owner == owner) playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun audioTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
