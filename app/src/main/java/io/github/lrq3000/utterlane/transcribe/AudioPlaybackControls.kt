package io.github.lrq3000.utterlane.transcribe

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrq3000.utterlane.R
import io.github.lrq3000.utterlane.UtterlaneApp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AudioPlaybackControls(model: TranscriptionDialogModel) {
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
    Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }) {
        if (!expanded) {
            TextButton(onClick = { source.audio?.let { player.play(owner, it.id, source.visualRate) } },
                enabled = source.audio != null && !source.closing, modifier = Modifier.testTag("audio_play")) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text(stringResource(R.string.audio_play))
            }
        } else {
            Row {
                TextButton(onClick = {
                    if (playback.playing) player.pause(owner)
                    else playback.audioId?.let { player.play(owner, it, source.visualRate) }
                }, enabled = !playback.preparing, modifier = Modifier.testTag("audio_pause")) {
                    Icon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                    Text(stringResource(if (playback.playing) R.string.audio_pause else R.string.audio_resume))
                }
                TextButton(onClick = { scrubbing = null; player.stop(owner) }, modifier = Modifier.testTag("audio_stop")) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(stringResource(R.string.audio_stop))
                }
            }
            if (playback.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
            val duration = playback.durationMs.coerceAtLeast(1)
            val fraction = scrubbing ?: (playback.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f)
            Slider(value = fraction, onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { player.seek(owner, (it.toDouble() * duration).toLong()) }
                    scrubbing = null
                }, enabled = !playback.preparing && playback.durationMs > 0, modifier = Modifier.testTag("audio_seek"))
            val total = if (playback.durationMs > 0) audioTime(playback.durationMs) else stringResource(R.string.audio_duration_unknown)
            Text("${audioTime(if (scrubbing == null) playback.positionMs else (fraction.toDouble() * duration).toLong())} / $total", style = MaterialTheme.typography.bodySmall)
        }
        if (playback.owner == owner) playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun audioTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
